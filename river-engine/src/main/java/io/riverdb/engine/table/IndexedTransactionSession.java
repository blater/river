package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionCommitParticipant;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockToken;
import java.nio.ByteBuffer;

/** One reusable transaction/session write set over the first indexed table. */
public final class IndexedTransactionSession implements TransactionCommitParticipant {
  private static final long TABLE_LOCK_ID = 1;
  private static final int MUTATION_NONE = 0;
  private static final int MAXIMUM_PENDING_INSERTS = 384;
  private static final int MAXIMUM_HELD_LOCKS = 384;
  private static final int MAXIMUM_SAVEPOINTS = 4;
  private static final int AUTOMATIC_VACUUM_OBSOLETE_VERSIONS = MAXIMUM_PENDING_INSERTS;

  private final TransactionManager manager;
  private final IndexedTable table;
  private final IndexedGroupCommitCoordinator groupCommit;
  private final IndexedVacuum automaticVacuum;
  private final int automaticVacuumThreshold;
  private final int automaticVacuumCapacityReserve;
  private final Transaction transaction;
  private final ByteBuffer pendingRows;
  private final int[] pendingOperations = new int[MAXIMUM_PENDING_INSERTS];
  private final long[] pendingKeys = new long[MAXIMUM_PENDING_INSERTS];
  private final int[] pendingPreviousRowIds = new int[MAXIMUM_PENDING_INSERTS];
  private final int[] pendingRowLengths = new int[MAXIMUM_PENDING_INSERTS];
  private final LockToken[] heldLocks = new LockToken[MAXIMUM_HELD_LOCKS];
  private final long[] lockedKeys = new long[MAXIMUM_HELD_LOCKS];
  private final long[] lockedUpperKeys = new long[MAXIMUM_HELD_LOCKS];
  private final boolean[] exclusiveLocks = new boolean[MAXIMUM_HELD_LOCKS];
  private final boolean[] rangeLocks = new boolean[MAXIMUM_HELD_LOCKS];
  private final boolean[] retainedMutations = new boolean[MAXIMUM_PENDING_INSERTS];
  private final IndexedSavepoint[] savepoints = new IndexedSavepoint[MAXIMUM_SAVEPOINTS];
  private final IndexedCommitResult commitResult = new IndexedCommitResult();
  private final TransactionOutcome maintenanceOutcome = new TransactionOutcome();
  private final HeapInsertResult preparedInsertResult = new HeapInsertResult();
  private final IndexedMutationTarget mutationTarget = new IndexedMutationTarget();
  private final int rowStride;
  private long committedSequence;
  private long copiedWriteSetBytes;
  private int pendingInsertCount;
  private int heldLockCount;
  private IndexedScanCursor activeScan;
  private int savepointCount;
  private boolean serializableScan;

  public IndexedTransactionSession(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      int maximumRowBytes) {
    this(transactionManager, indexedTable, maximumRowBytes, null, null);
  }

  public IndexedTransactionSession(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      int maximumRowBytes,
      IndexedGroupCommitCoordinator groupCommitCoordinator) {
    this(transactionManager, indexedTable, maximumRowBytes, groupCommitCoordinator, null);
  }

  public IndexedTransactionSession(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      int maximumRowBytes,
      IndexedGroupCommitCoordinator groupCommitCoordinator,
      IndexedVacuum versionVacuum) {
    this(
        transactionManager,
        indexedTable,
        maximumRowBytes,
        groupCommitCoordinator,
        versionVacuum,
        AUTOMATIC_VACUUM_OBSOLETE_VERSIONS,
        MAXIMUM_PENDING_INSERTS);
  }

  IndexedTransactionSession(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      int maximumRowBytes,
      IndexedGroupCommitCoordinator groupCommitCoordinator,
      IndexedVacuum versionVacuum,
      int vacuumThreshold) {
    this(
        transactionManager,
        indexedTable,
        maximumRowBytes,
        groupCommitCoordinator,
        versionVacuum,
        vacuumThreshold,
        MAXIMUM_PENDING_INSERTS);
  }

  IndexedTransactionSession(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      int maximumRowBytes,
      IndexedGroupCommitCoordinator groupCommitCoordinator,
      IndexedVacuum versionVacuum,
      int vacuumThreshold,
      int vacuumCapacityReserve) {
    manager = transactionManager;
    table = indexedTable;
    groupCommit = groupCommitCoordinator;
    automaticVacuum = versionVacuum;
    automaticVacuumThreshold = vacuumThreshold;
    automaticVacuumCapacityReserve = vacuumCapacityReserve;
    transaction = new Transaction(transactionManager.maximumActiveTransactions());
    rowStride = maximumRowBytes;
    pendingRows = ByteBuffer.allocateDirect(maximumRowBytes * MAXIMUM_PENDING_INSERTS);
    for (int index = 0; index < heldLocks.length; index++) {
      heldLocks[index] = new LockToken();
    }
  }

  public Transaction transaction() {
    return transaction;
  }

  public long copiedWriteSetBytes() {
    return copiedWriteSetBytes;
  }

  /** Current write-set position for higher-level transactional metadata coordination. */
  public int pendingMutationCount() {
    return pendingInsertCount;
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (transaction.isActiveHandle() || activeScan != null || savepointCount != 0) {
      return StatusCode.CONFLICT;
    }
    pendingInsertCount = 0;
    heldLockCount = 0;
    committedSequence = 0;
    serializableScan = false;
    for (LockToken heldLock : heldLocks) {
      StatusCode status = heldLock.reset();
      if (!status.isOk()) {
        return status;
      }
    }
    StatusCode maintenance = maintainVersions();
    if (!maintenance.isOk()) {
      return maintenance;
    }
    return manager.begin(isolationLevel, table, transaction);
  }

  private StatusCode maintainVersions() {
    if (automaticVacuum == null) {
      return StatusCode.OK;
    }
    int obsoleteVersions = table.obsoleteVersionCount();
    if (obsoleteVersions < 0) {
      return StatusCode.CORRUPTION;
    }
    if (obsoleteVersions == 0) {
      return StatusCode.OK;
    }
    long reservedRows = (long) (manager.activeTransactionCount() + 1)
        * automaticVacuumCapacityReserve;
    boolean pressure = table.remainingVersionCapacity() < reservedRows;
    if (obsoleteVersions < automaticVacuumThreshold && !pressure) {
      return StatusCode.OK;
    }
    StatusCode status = automaticVacuum.runAutomatic(maintenanceOutcome, pressure);
    if (status == StatusCode.RETRY && pressure) {
      return status;
    }
    if (status.isOk()
        || status == StatusCode.CONFLICT
        || status == StatusCode.RETRY
        || status == StatusCode.RESOURCE_EXHAUSTED) {
      return StatusCode.OK;
    }
    return status;
  }

  public StatusCode insert(long key, ByteBuffer row) {
    if (transaction.state() != TransactionState.ACTIVE
        || key == Long.MAX_VALUE
        || row == null
        || !row.hasRemaining()
        || row.remaining() > rowStride) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingInsertCount >= MAXIMUM_PENDING_INSERTS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = acquireExclusiveKey(key);
    if (!status.isOk()) {
      return status;
    }
    int pendingIndex = findLatestPendingIndex(key);
    if (pendingIndex >= 0) {
      int pendingOperation = pendingOperations[pendingIndex];
      if (pendingOperation != IndexedTable.MUTATION_DELETE
          && pendingOperation != MUTATION_NONE) {
        return StatusCode.CONFLICT;
      }
      appendPending(
          pendingOperation == IndexedTable.MUTATION_DELETE
              ? IndexedTable.MUTATION_UPDATE : IndexedTable.MUTATION_INSERT,
          key,
          pendingPreviousRowIds[pendingIndex],
          row,
          row.position(),
          row.remaining(),
          true);
      return StatusCode.OK;
    }
    status = refreshForWrite();
    if (status.isOk()) {
      status = table.prepareInsert(
          transaction.snapshot().visibleCommitSequence(), key, mutationTarget);
    }
    if (!status.isOk()) {
      return status;
    }
    appendPending(
        IndexedTable.MUTATION_INSERT,
        key,
        mutationTarget.rowId(),
        row,
        row.position(),
        row.remaining(),
        true);
    return StatusCode.OK;
  }

  public StatusCode update(long key, ByteBuffer row) {
    if (transaction.state() != TransactionState.ACTIVE
        || key == Long.MAX_VALUE
        || row == null
        || !row.hasRemaining()
        || row.remaining() > rowStride) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingInsertCount >= MAXIMUM_PENDING_INSERTS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = acquireExclusiveKey(key);
    if (!status.isOk()) {
      return status;
    }
    int pendingIndex = findLatestPendingIndex(key);
    if (pendingIndex >= 0) {
      int pendingOperation = pendingOperations[pendingIndex];
      if (pendingOperation != IndexedTable.MUTATION_INSERT
          && pendingOperation != IndexedTable.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      appendPending(
          pendingOperation,
          key,
          pendingPreviousRowIds[pendingIndex],
          row,
          row.position(),
          row.remaining(),
          true);
      return StatusCode.OK;
    }
    status = refreshForWrite();
    if (status.isOk()) {
      status = table.prepareMutation(
          transaction.snapshot().visibleCommitSequence(), key, mutationTarget);
    }
    if (!status.isOk()) {
      return status;
    }
    appendPending(
        IndexedTable.MUTATION_UPDATE,
        key,
        mutationTarget.rowId(),
        row,
        row.position(),
        row.remaining(),
        true);
    return StatusCode.OK;
  }

  public StatusCode delete(long key) {
    if (transaction.state() != TransactionState.ACTIVE || key == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingInsertCount >= MAXIMUM_PENDING_INSERTS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = acquireExclusiveKey(key);
    if (!status.isOk()) {
      return status;
    }
    int pendingIndex = findLatestPendingIndex(key);
    if (pendingIndex >= 0) {
      int pendingOperation = pendingOperations[pendingIndex];
      if (pendingOperation != IndexedTable.MUTATION_INSERT
          && pendingOperation != IndexedTable.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      appendPendingDeletion(
          pendingOperation == IndexedTable.MUTATION_INSERT
              ? MUTATION_NONE : IndexedTable.MUTATION_DELETE,
          key,
          pendingPreviousRowIds[pendingIndex]);
      return StatusCode.OK;
    }
    status = refreshForWrite();
    if (status.isOk()) {
      status = table.prepareMutation(
          transaction.snapshot().visibleCommitSequence(), key, mutationTarget);
    }
    if (!status.isOk()) {
      return status;
    }
    appendPendingDeletion(
        IndexedTable.MUTATION_DELETE, key, mutationTarget.rowId());
    return StatusCode.OK;
  }

  private void appendPendingDeletion(int operation, long key, int previousRowId) {
    int destinationStart = pendingInsertCount * rowStride;
    pendingRows.limit(pendingRows.capacity());
    pendingRows.put(destinationStart, (byte) 0);
    pendingOperations[pendingInsertCount] = operation;
    pendingKeys[pendingInsertCount] = key;
    pendingPreviousRowIds[pendingInsertCount] = previousRowId;
    pendingRowLengths[pendingInsertCount] = 1;
    pendingInsertCount++;
  }

  private void appendPending(
      int operation,
      long key,
      int previousRowId,
      ByteBuffer source,
      int sourceStart,
      int rowBytes,
      boolean countCopy) {
    int destinationStart = pendingInsertCount * rowStride;
    pendingRows.limit(pendingRows.capacity());
    for (int index = 0; index < rowBytes; index++) {
      pendingRows.put(destinationStart + index, source.get(sourceStart + index));
    }
    if (countCopy) {
      copiedWriteSetBytes += rowBytes;
    }
    pendingOperations[pendingInsertCount] = operation;
    pendingKeys[pendingInsertCount] = key;
    pendingPreviousRowIds[pendingInsertCount] = previousRowId;
    pendingRowLengths[pendingInsertCount] = rowBytes;
    pendingInsertCount++;
  }

  public StatusCode fetchByKey(long key, HeapRowResult result) {
    if (transaction.state() != TransactionState.ACTIVE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = pendingInsertCount - 1; index >= 0; index--) {
      if (pendingKeys[index] == key) {
        if (pendingOperations[index] == IndexedTable.MUTATION_DELETE
            || pendingOperations[index] == MUTATION_NONE) {
          result.reset();
          return StatusCode.CONFLICT;
        }
        result.set(
            pendingRows,
            0,
            index * rowStride,
            pendingRowLengths[index]);
        return StatusCode.OK;
      }
    }
    if (transaction.isolationLevel() == IsolationLevel.SERIALIZABLE
        && findHeldLock(key) < 0) {
      if (heldLockCount >= MAXIMUM_HELD_LOCKS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      StatusCode status = manager.tryAcquireSharedKey(
          transaction, TABLE_LOCK_ID, key, heldLocks[heldLockCount]);
      if (!status.isOk()) {
        return status;
      }
      lockedKeys[heldLockCount] = key;
      lockedUpperKeys[heldLockCount] = 0;
      exclusiveLocks[heldLockCount] = false;
      rangeLocks[heldLockCount] = false;
      heldLockCount++;
    }
    if (transaction.isolationLevel() == IsolationLevel.READ_COMMITTED) {
      StatusCode status = manager.refreshReadCommitted(
          transaction, table);
      if (!status.isOk()) {
        return status;
      }
    }
    return table.fetchByKeyAt(
        transaction.snapshot().visibleCommitSequence(), key, result);
  }

  public StatusCode beginScan(
      long lowerKey,
      long upperKey,
      IndexedScanCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (transaction.state() != TransactionState.ACTIVE) {
      return StatusCode.CONFLICT;
    }
    if (transaction.isolationLevel() == IsolationLevel.READ_COMMITTED) {
      StatusCode status = manager.refreshReadCommitted(transaction, table);
      if (!status.isOk()) {
        return status;
      }
    }
    StatusCode status = table.beginScan(
        transaction.snapshot().visibleCommitSequence(), lowerKey, upperKey, cursor);
    if (status.isOk()) {
      status = cursor.attach(this);
    }
    if (status.isOk() && transaction.isolationLevel() == IsolationLevel.SERIALIZABLE) {
      status = acquireSharedRange(lowerKey, upperKey);
    }
    if (!status.isOk() && cursor.isSessionOwnedBy(this)) {
      StatusCode close = table.closeScan(cursor);
      if (!close.isOk()) {
        return close;
      }
    }
    if (status.isOk()) {
      activeScan = cursor;
      if (transaction.isolationLevel() == IsolationLevel.SERIALIZABLE) {
        serializableScan = true;
      }
    }
    return status;
  }

  public StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    if (transaction.state() != TransactionState.ACTIVE
        || cursor == null
        || cursor != activeScan
        || !cursor.isSessionOwnedBy(this)
        || result == null) {
      return StatusCode.CONFLICT;
    }
    result.reset();
    while (true) {
      if (!cursor.hasCommittedLookahead() && !cursor.committedExhausted()) {
        StatusCode status = table.nextScan(cursor, cursor.committedLookahead());
        if (status.isOk()) {
          cursor.setCommittedLookahead(true);
        } else if (status == StatusCode.CONFLICT) {
          cursor.setCommittedExhausted();
        } else {
          return status;
        }
      }
      int pendingIndex = nextPendingIndex(cursor);
      long pendingKey = pendingIndex >= 0 ? pendingKeys[pendingIndex] : Long.MAX_VALUE;
      long committedKey = cursor.hasCommittedLookahead()
          ? cursor.committedLookahead().key() : Long.MAX_VALUE;
      if (pendingIndex < 0 && !cursor.hasCommittedLookahead()) {
        return StatusCode.CONFLICT;
      }
      if (pendingKey <= committedKey) {
        if (pendingKey == committedKey) {
          cursor.setCommittedLookahead(false);
        }
        cursor.returned(pendingKey);
        if (pendingOperations[pendingIndex] == IndexedTable.MUTATION_DELETE
            || pendingOperations[pendingIndex] == MUTATION_NONE) {
          continue;
        }
        result.row().set(
            pendingRows,
            0,
            pendingIndex * rowStride,
            pendingRowLengths[pendingIndex]);
        result.set(pendingKey);
        return StatusCode.OK;
      }
      result.copyFrom(cursor.committedLookahead());
      cursor.setCommittedLookahead(false);
      cursor.returned(committedKey);
      return StatusCode.OK;
    }
  }

  public StatusCode closeScan(IndexedScanCursor cursor) {
    if (cursor == null || cursor != activeScan || !cursor.isSessionOwnedBy(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = table.closeScan(cursor);
    if (status.isOk()) {
      activeScan = null;
    }
    return status;
  }

  public StatusCode createSavepoint(IndexedSavepoint savepoint) {
    if (savepoint == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (transaction.state() != TransactionState.ACTIVE || activeScan != null) {
      return StatusCode.CONFLICT;
    }
    if (savepointCount >= savepoints.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = savepoint.claim(
        this, transaction.transactionId(), pendingInsertCount);
    if (status.isOk()) {
      savepoints[savepointCount++] = savepoint;
    }
    return status;
  }

  /** Rolls back pending mutations but deliberately retains acquired locks until transaction end. */
  public StatusCode rollbackToSavepoint(IndexedSavepoint savepoint) {
    if (savepoint == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (transaction.state() != TransactionState.ACTIVE
        || activeScan != null
        || !savepoint.isOwnedBy(this, transaction.transactionId())
        || savepoint.pendingMutationCount() > pendingInsertCount) {
      return StatusCode.CONFLICT;
    }
    int savepointIndex = findSavepoint(savepoint);
    if (savepointIndex < 0) {
      return StatusCode.NOT_OWNER;
    }
    clearPendingFrom(savepoint.pendingMutationCount());
    completeSavepointsAfter(savepointIndex + 1);
    return StatusCode.OK;
  }

  public StatusCode releaseSavepoint(IndexedSavepoint savepoint) {
    if (savepoint == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!savepoint.isOwnedBy(this, transaction.transactionId())) {
      return StatusCode.NOT_OWNER;
    }
    int savepointIndex = findSavepoint(savepoint);
    if (savepointIndex < 0) {
      return StatusCode.NOT_OWNER;
    }
    completeSavepointsAfter(savepointIndex);
    return StatusCode.OK;
  }

  public StatusCode cancelLockWait() {
    return manager.cancelLockWait(transaction);
  }

  private int nextPendingIndex(IndexedScanCursor cursor) {
    int selected = -1;
    long selectedKey = Long.MAX_VALUE;
    for (int index = 0; index < pendingInsertCount; index++) {
      long key = pendingKeys[index];
      if (findLatestPendingIndex(key) == index
          && key >= cursor.lowerKey()
          && key < cursor.upperKey()
          && cursor.afterLastReturned(key)
          && key < selectedKey) {
        selected = index;
        selectedKey = key;
      }
    }
    return selected;
  }

  public StatusCode commit(TransactionOutcome result) {
    if (activeScan != null) {
      return StatusCode.CONFLICT;
    }
    compactWriteSet();
    if (pendingInsertCount == 0) {
      StatusCode status = manager.commitReadOnly(transaction, result);
      if (!transaction.isActiveHandle()) {
        StatusCode release = releaseLocks();
        clearWriteSet();
        if (status.isOk() && !release.isOk()) {
          return release;
        }
      }
      return status;
    }
    if (manager.hasLockConflict(transaction)) {
      return completeCoordinatedCommit(manager.commit(transaction, this, result));
    }
    if (groupCommit != null && eligibleForCommitGroup()) {
      return groupCommit.commit(this, result);
    }
    return completeCoordinatedCommit(manager.commit(transaction, this, result));
  }

  boolean eligibleForCommitGroup() {
    return transaction.state() == TransactionState.ACTIVE
        && activeScan == null
        && pendingInsertCount > 0
        && !manager.hasLockConflict(transaction)
        && !serializableScan;
  }

  Transaction groupTransaction() {
    return transaction;
  }

  StatusCode preflightPreparedWrites(IndexedPageStore store) {
    return containsNonInsertMutation()
        ? store.preflightPreparedMutationBatch(
            pendingOperations,
            pendingKeys,
            pendingPreviousRowIds,
            pendingRows,
            rowStride,
            pendingRowLengths,
            pendingInsertCount)
        : store.preflightPreparedInsertBatch(
            pendingKeys,
            pendingRows,
            rowStride,
            pendingRowLengths,
            pendingInsertCount);
  }

  StatusCode appendPreparedWrites(IndexedPageStore store, long commitSequence) {
    StatusCode status = containsNonInsertMutation()
        ? store.appendPreparedMutationBatch(
            transaction.transactionId(),
            commitSequence,
            pendingOperations,
            pendingKeys,
            pendingPreviousRowIds,
            pendingRows,
            rowStride,
            pendingRowLengths,
            pendingInsertCount,
            preparedInsertResult)
        : store.appendPreparedInsertBatch(
            transaction.transactionId(),
            commitSequence,
            pendingKeys,
            pendingRows,
            rowStride,
            pendingRowLengths,
            pendingInsertCount,
            preparedInsertResult);
    if (status.isOk()) {
      commitResult.set(preparedInsertResult.rowId(), commitSequence);
      committedSequence = commitSequence;
    }
    return status;
  }

  StatusCode commitDirect(TransactionOutcome result) {
    return manager.commit(transaction, this, result);
  }

  StatusCode completeCoordinatedCommit(StatusCode status) {
    if (!transaction.isActiveHandle()) {
      StatusCode release = releaseLocks();
      clearWriteSet();
      if (status.isOk() && !release.isOk()) {
        return release;
      }
    }
    return status;
  }

  public StatusCode abort(TransactionOutcome result) {
    if (activeScan != null) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = manager.abort(transaction, result);
    if (status.isOk()) {
      StatusCode release = releaseLocks();
      clearWriteSet();
      if (!release.isOk()) {
        return release;
      }
    }
    return status;
  }

  @Override
  public StatusCode commit(long transactionId) {
    pendingRows.position(0);
    pendingRows.limit(pendingRows.capacity());
    StatusCode status;
    if (containsNonInsertMutation()) {
      status = table.commitMutations(
          transactionId,
          pendingOperations,
          pendingKeys,
          pendingPreviousRowIds,
          pendingRows,
          rowStride,
          pendingRowLengths,
          pendingInsertCount,
          commitResult);
    } else {
      status = table.commitInserts(
          transactionId,
          pendingKeys,
          pendingRows,
          rowStride,
          pendingRowLengths,
          pendingInsertCount,
          commitResult);
    }
    committedSequence = status.isOk() ? commitResult.commitSequence() : 0;
    return status;
  }

  @Override
  public long committedSequence() {
    return committedSequence;
  }

  private StatusCode releaseLocks() {
    StatusCode result = StatusCode.OK;
    for (int index = 0; index < heldLockCount; index++) {
      LockToken heldLock = heldLocks[index];
      if (!heldLock.isActive()) {
        continue;
      }
      StatusCode status = manager.release(heldLock);
      if (result.isOk() && !status.isOk()) {
        result = status;
      }
      lockedKeys[index] = 0;
      lockedUpperKeys[index] = 0;
      exclusiveLocks[index] = false;
      rangeLocks[index] = false;
    }
    heldLockCount = 0;
    return result;
  }

  private void clearWriteSet() {
    clearPendingFrom(0);
    serializableScan = false;
    completeSavepointsAfter(0);
  }

  private void clearPendingFrom(int first) {
    for (int index = first; index < pendingInsertCount; index++) {
      pendingKeys[index] = 0;
      pendingOperations[index] = 0;
      pendingPreviousRowIds[index] = 0;
      pendingRowLengths[index] = 0;
    }
    pendingInsertCount = first;
  }

  private int findSavepoint(IndexedSavepoint savepoint) {
    for (int index = savepointCount - 1; index >= 0; index--) {
      if (savepoints[index] == savepoint) {
        return index;
      }
    }
    return -1;
  }

  private void completeSavepointsAfter(int first) {
    for (int index = savepointCount - 1; index >= first; index--) {
      savepoints[index].complete();
      savepoints[index] = null;
    }
    savepointCount = first;
  }

  private int findHeldLock(long key) {
    for (int index = 0; index < heldLockCount; index++) {
      if (!rangeLocks[index] && lockedKeys[index] == key) {
        return index;
      }
    }
    return -1;
  }

  private StatusCode acquireExclusiveKey(long key) {
    int lockIndex = findHeldLock(key);
    if (lockIndex >= 0) {
      if (exclusiveLocks[lockIndex]) {
        return StatusCode.OK;
      }
      StatusCode status = manager.upgradeKey(transaction, heldLocks[lockIndex]);
      if (status.isOk()) {
        exclusiveLocks[lockIndex] = true;
      }
      return status;
    }
    if (heldLockCount >= MAXIMUM_HELD_LOCKS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = manager.tryAcquireKey(
        transaction, TABLE_LOCK_ID, key, heldLocks[heldLockCount]);
    if (status.isOk()) {
      lockedKeys[heldLockCount] = key;
      lockedUpperKeys[heldLockCount] = 0;
      exclusiveLocks[heldLockCount] = true;
      rangeLocks[heldLockCount] = false;
      heldLockCount++;
    }
    return status;
  }

  private StatusCode acquireSharedRange(long lowerKey, long upperKey) {
    for (int index = 0; index < heldLockCount; index++) {
      if (rangeLocks[index]
          && lockedKeys[index] <= lowerKey
          && lockedUpperKeys[index] >= upperKey) {
        return StatusCode.OK;
      }
    }
    if (heldLockCount >= MAXIMUM_HELD_LOCKS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = manager.tryAcquireSharedRange(
        transaction, lowerKey, upperKey, heldLocks[heldLockCount]);
    if (status.isOk()) {
      lockedKeys[heldLockCount] = lowerKey;
      lockedUpperKeys[heldLockCount] = upperKey;
      exclusiveLocks[heldLockCount] = false;
      rangeLocks[heldLockCount] = true;
      heldLockCount++;
    }
    return status;
  }

  private StatusCode refreshForWrite() {
    if (transaction.isolationLevel() != IsolationLevel.READ_COMMITTED) {
      return StatusCode.OK;
    }
    return manager.refreshReadCommitted(transaction, table);
  }

  private boolean containsNonInsertMutation() {
    for (int index = 0; index < pendingInsertCount; index++) {
      if (pendingOperations[index] != IndexedTable.MUTATION_INSERT
          || pendingPreviousRowIds[index] != 0) {
        return true;
      }
    }
    return false;
  }

  private int findLatestPendingIndex(long key) {
    for (int index = pendingInsertCount - 1; index >= 0; index--) {
      if (pendingKeys[index] == key) {
        return index;
      }
    }
    return -1;
  }

  private void compactWriteSet() {
    int originalCount = pendingInsertCount;
    for (int index = 0; index < originalCount; index++) {
      boolean latest = true;
      for (int later = index + 1; later < originalCount; later++) {
        if (pendingKeys[later] == pendingKeys[index]) {
          latest = false;
          break;
        }
      }
      retainedMutations[index] = latest && pendingOperations[index] != MUTATION_NONE;
    }
    int output = 0;
    for (int index = 0; index < originalCount; index++) {
      if (!retainedMutations[index]) {
        continue;
      }
      if (output != index) {
        int sourceOffset = index * rowStride;
        int targetOffset = output * rowStride;
        int rowBytes = pendingRowLengths[index];
        for (int byteIndex = 0; byteIndex < rowBytes; byteIndex++) {
          pendingRows.put(targetOffset + byteIndex, pendingRows.get(sourceOffset + byteIndex));
        }
        pendingOperations[output] = pendingOperations[index];
        pendingKeys[output] = pendingKeys[index];
        pendingPreviousRowIds[output] = pendingPreviousRowIds[index];
        pendingRowLengths[output] = rowBytes;
      }
      output++;
    }
    for (int index = 0; index < originalCount; index++) {
      retainedMutations[index] = false;
      if (index >= output) {
        pendingOperations[index] = 0;
        pendingKeys[index] = 0;
        pendingPreviousRowIds[index] = 0;
        pendingRowLengths[index] = 0;
      }
    }
    pendingInsertCount = output;
  }
}
