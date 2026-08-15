package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
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
  private static final int MUTATION_NONE = 0;
  private static final int MAXIMUM_PENDING_INSERTS = 384;
  private static final int MAXIMUM_HELD_LOCKS = 384;
  private static final int MAXIMUM_SAVEPOINTS = 4;
  private static final int MAXIMUM_ACTIVE_SCANS = 32;
  private static final int AUTOMATIC_VACUUM_OBSOLETE_VERSIONS = MAXIMUM_PENDING_INSERTS;

  private final TransactionManager manager;
  private final IndexedTable table;
  private final IndexedGroupCommitCoordinator groupCommit;
  private final IndexedVacuum automaticVacuum;
  private final int automaticVacuumThreshold;
  private final int automaticVacuumCapacityReserve;
  private final Transaction transaction;
  private final PendingMutationBuffer pendingMutations;
  private final LockToken[] heldLocks = new LockToken[MAXIMUM_HELD_LOCKS];
  private final long[] lockedKeys = new long[MAXIMUM_HELD_LOCKS];
  private final long[] lockedUpperKeys = new long[MAXIMUM_HELD_LOCKS];
  private final int[] lockedSpaces = new int[MAXIMUM_HELD_LOCKS];
  private final int[] lockedUpperSpaces = new int[MAXIMUM_HELD_LOCKS];
  private final boolean[] exclusiveLocks = new boolean[MAXIMUM_HELD_LOCKS];
  private final boolean[] rangeLocks = new boolean[MAXIMUM_HELD_LOCKS];
  private final IndexedSavepoint[] savepoints = new IndexedSavepoint[MAXIMUM_SAVEPOINTS];
  private final IndexedCommitResult commitResult = new IndexedCommitResult();
  private final TransactionOutcome maintenanceOutcome = new TransactionOutcome();
  private final HeapInsertResult preparedInsertResult = new HeapInsertResult();
  private final IndexedMutationTarget mutationTarget = new IndexedMutationTarget();
  private long committedSequence;
  private long copiedWriteSetBytes;
  private int heldLockCount;
  private final IndexedScanCursor[] activeScans =
      new IndexedScanCursor[MAXIMUM_ACTIVE_SCANS];
  private int activeScanCount;
  private int savepointCount;
  private boolean serializableScan;
  private boolean statementActive;

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
    pendingMutations = new PendingMutationBuffer(MAXIMUM_PENDING_INSERTS, maximumRowBytes);
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
    return pendingMutations.count();
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (transaction.isActiveHandle()
        || activeScanCount != 0
        || savepointCount != 0
        || statementActive) {
      return StatusCode.CONFLICT;
    }
    pendingMutations.truncate(0);
    heldLockCount = 0;
    committedSequence = 0;
    serializableScan = false;
    statementActive = false;
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

  /** Pins one statement snapshot while higher layers perform related storage calls. */
  public StatusCode beginStatement() {
    if (transaction.state() != TransactionState.ACTIVE
        || statementActive
        || activeScanCount != 0) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = transaction.isolationLevel() == IsolationLevel.READ_COMMITTED
        ? manager.refreshReadCommitted(transaction, table) : StatusCode.OK;
    if (status.isOk()) {
      statementActive = true;
    }
    return status;
  }

  public StatusCode completeStatement() {
    if (transaction.state() != TransactionState.ACTIVE
        || !statementActive
        || activeScanCount != 0) {
      return StatusCode.CONFLICT;
    }
    statementActive = false;
    return StatusCode.OK;
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

  public StatusCode insert(int space, long key, ByteBuffer row) {
    if (transaction.state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)
        || row == null
        || !row.hasRemaining()
        || row.remaining() > pendingMutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingMutations.count() >= pendingMutations.capacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = acquireExclusiveKey(space, key);
    if (!status.isOk()) {
      return status;
    }
    int pendingIndex = findLatestPendingIndex(space, key);
    if (pendingIndex >= 0) {
      int pendingOperation = pendingMutations.operationAt(pendingIndex);
      if (pendingOperation != IndexedWalCodec.MUTATION_DELETE
          && pendingOperation != MUTATION_NONE) {
        return StatusCode.CONFLICT;
      }
      appendPending(
          pendingOperation == IndexedWalCodec.MUTATION_DELETE
              ? IndexedWalCodec.MUTATION_UPDATE : IndexedWalCodec.MUTATION_INSERT,
          space,
          key,
          pendingMutations.previousRowIdAt(pendingIndex),
          row,
          row.position(),
          row.remaining(),
          true);
      return StatusCode.OK;
    }
    status = refreshForWrite();
    if (status.isOk()) {
      status = table.prepareInsert(
          transaction.snapshot().visibleCommitSequence(), space, key, mutationTarget);
    }
    if (!status.isOk()) {
      return status;
    }
    appendPending(
        IndexedWalCodec.MUTATION_INSERT,
        space,
        key,
        mutationTarget.rowId(),
        row,
        row.position(),
        row.remaining(),
        true);
    return StatusCode.OK;
  }

  public StatusCode update(int space, long key, ByteBuffer row) {
    if (transaction.state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)
        || row == null
        || !row.hasRemaining()
        || row.remaining() > pendingMutations.rowStride()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingMutations.count() >= pendingMutations.capacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = acquireExclusiveKey(space, key);
    if (!status.isOk()) {
      return status;
    }
    int pendingIndex = findLatestPendingIndex(space, key);
    if (pendingIndex >= 0) {
      int pendingOperation = pendingMutations.operationAt(pendingIndex);
      if (pendingOperation != IndexedWalCodec.MUTATION_INSERT
          && pendingOperation != IndexedWalCodec.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      appendPending(
          pendingOperation,
          space,
          key,
          pendingMutations.previousRowIdAt(pendingIndex),
          row,
          row.position(),
          row.remaining(),
          true);
      return StatusCode.OK;
    }
    status = refreshForWrite();
    if (status.isOk()) {
      status = table.prepareMutation(
          transaction.snapshot().visibleCommitSequence(), space, key, mutationTarget);
    }
    if (!status.isOk()) {
      return status;
    }
    appendPending(
        IndexedWalCodec.MUTATION_UPDATE,
        space,
        key,
        mutationTarget.rowId(),
        row,
        row.position(),
        row.remaining(),
        true);
    return StatusCode.OK;
  }

  public StatusCode delete(int space, long key) {
    if (transaction.state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingMutations.count() >= pendingMutations.capacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = acquireExclusiveKey(space, key);
    if (!status.isOk()) {
      return status;
    }
    int pendingIndex = findLatestPendingIndex(space, key);
    if (pendingIndex >= 0) {
      int pendingOperation = pendingMutations.operationAt(pendingIndex);
      if (pendingOperation != IndexedWalCodec.MUTATION_INSERT
          && pendingOperation != IndexedWalCodec.MUTATION_UPDATE) {
        return StatusCode.CONFLICT;
      }
      appendPendingDeletion(
          pendingOperation == IndexedWalCodec.MUTATION_INSERT
              ? MUTATION_NONE : IndexedWalCodec.MUTATION_DELETE,
          space,
          key,
          pendingMutations.previousRowIdAt(pendingIndex));
      return StatusCode.OK;
    }
    status = refreshForWrite();
    if (status.isOk()) {
      status = table.prepareMutation(
          transaction.snapshot().visibleCommitSequence(), space, key, mutationTarget);
    }
    if (!status.isOk()) {
      return status;
    }
    appendPendingDeletion(
        IndexedWalCodec.MUTATION_DELETE, space, key, mutationTarget.rowId());
    return StatusCode.OK;
  }

  private void appendPendingDeletion(
      int operation, int space, long key, int previousRowId) {
    pendingMutations.appendDeletion(operation, space, key, previousRowId);
  }

  private void appendPending(
      int operation,
      int space,
      long key,
      int previousRowId,
      ByteBuffer source,
      int sourceStart,
      int rowBytes,
      boolean countCopy) {
    pendingMutations.append(
        operation, space, key, previousRowId, source, sourceStart, rowBytes);
    if (countCopy) {
      copiedWriteSetBytes += rowBytes;
    }
  }

  public StatusCode fetchByKey(int space, long key, HeapRowResult result) {
    if (transaction.state() != TransactionState.ACTIVE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = pendingMutations.count() - 1; index >= 0; index--) {
      if (pendingMutations.spaceAt(index) == space
          && pendingMutations.keyAt(index) == key) {
        if (pendingMutations.operationAt(index) == IndexedWalCodec.MUTATION_DELETE
            || pendingMutations.operationAt(index) == MUTATION_NONE) {
          result.reset();
          return StatusCode.CONFLICT;
        }
        pendingMutations.setRowResult(index, result);
        return StatusCode.OK;
      }
    }
    if (transaction.isolationLevel() == IsolationLevel.SERIALIZABLE) {
      StatusCode status = protectKey(space, key);
      if (!status.isOk()) {
        return status;
      }
    }
    if (transaction.isolationLevel() == IsolationLevel.READ_COMMITTED
        && !statementActive) {
      StatusCode status = manager.refreshReadCommitted(
          transaction, table);
      if (!status.isOk()) {
        return status;
      }
    }
    return table.fetchByKeyAt(
        transaction.snapshot().visibleCommitSequence(), space, key, result);
  }

  /** Holds a shared key lock through transaction completion for integrity checks. */
  public StatusCode protectKey(int space, long key) {
    if (transaction.state() != TransactionState.ACTIVE
        || !OrderedKey.isFiniteSpace(space)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (findHeldLock(space, key) >= 0) {
      return StatusCode.OK;
    }
    if (heldLockCount >= MAXIMUM_HELD_LOCKS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = manager.tryAcquireSharedKey(
        transaction, space, key, heldLocks[heldLockCount]);
    if (status.isOk()) {
      lockedKeys[heldLockCount] = key;
      lockedSpaces[heldLockCount] = space;
      lockedUpperKeys[heldLockCount] = 0;
      exclusiveLocks[heldLockCount] = false;
      rangeLocks[heldLockCount] = false;
      heldLockCount++;
    }
    return status;
  }

  public StatusCode beginScan(
      int lowerSpace,
      long lowerKey,
      int upperSpace,
      long upperKey,
      IndexedScanCursor cursor) {
    if (cursor == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (transaction.state() != TransactionState.ACTIVE) {
      return StatusCode.CONFLICT;
    }
    if (activeScanCount >= activeScans.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (transaction.isolationLevel() == IsolationLevel.READ_COMMITTED
        && !statementActive
        && activeScanCount == 0) {
      StatusCode status = manager.refreshReadCommitted(transaction, table);
      if (!status.isOk()) {
        return status;
      }
    }
    StatusCode status = table.beginScan(
        transaction.snapshot().visibleCommitSequence(),
        lowerSpace, lowerKey, upperSpace, upperKey, cursor);
    if (status.isOk()) {
      status = cursor.attach(this);
    }
    if (status.isOk() && transaction.isolationLevel() == IsolationLevel.SERIALIZABLE) {
      status = acquireSharedRange(lowerSpace, lowerKey, upperSpace, upperKey);
    }
    if (!status.isOk() && cursor.isSessionOwnedBy(this)) {
      StatusCode close = table.closeScan(cursor);
      if (!close.isOk()) {
        return close;
      }
    }
    if (status.isOk()) {
      activeScans[activeScanCount++] = cursor;
      if (transaction.isolationLevel() == IsolationLevel.SERIALIZABLE) {
        serializableScan = true;
      }
    }
    return status;
  }

  public StatusCode nextScan(IndexedScanCursor cursor, IndexedScanResult result) {
    if (transaction.state() != TransactionState.ACTIVE
        || cursor == null
        || findActiveScan(cursor) < 0
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
      if (pendingIndex < 0 && !cursor.hasCommittedLookahead()) {
        return StatusCode.CONFLICT;
      }
      boolean returnPending = pendingIndex >= 0
          && (!cursor.hasCommittedLookahead()
              || OrderedKey.compare(
                  pendingMutations.spaceAt(pendingIndex),
                  pendingMutations.keyAt(pendingIndex),
                  cursor.committedLookahead().keySpace(),
                  cursor.committedLookahead().key()) <= 0);
      if (returnPending) {
        int pendingSpace = pendingMutations.spaceAt(pendingIndex);
        long pendingKey = pendingMutations.keyAt(pendingIndex);
        if (cursor.hasCommittedLookahead()
            && OrderedKey.equal(
                pendingSpace, pendingKey,
                cursor.committedLookahead().keySpace(),
                cursor.committedLookahead().key())) {
          cursor.setCommittedLookahead(false);
        }
        cursor.returned(pendingSpace, pendingKey);
        if (pendingMutations.operationAt(pendingIndex) == IndexedWalCodec.MUTATION_DELETE
            || pendingMutations.operationAt(pendingIndex) == MUTATION_NONE) {
          continue;
        }
        pendingMutations.setRowResult(pendingIndex, result.row());
        result.set(pendingSpace, pendingKey);
        return StatusCode.OK;
      }
      result.copyFrom(cursor.committedLookahead());
      cursor.setCommittedLookahead(false);
      cursor.returned(result.keySpace(), result.key());
      return StatusCode.OK;
    }
  }

  public StatusCode closeScan(IndexedScanCursor cursor) {
    int active = findActiveScan(cursor);
    if (cursor == null || active < 0 || !cursor.isSessionOwnedBy(this)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = table.closeScan(cursor);
    if (status.isOk()) {
      activeScanCount--;
      activeScans[active] = activeScans[activeScanCount];
      activeScans[activeScanCount] = null;
    }
    return status;
  }

  public StatusCode createSavepoint(IndexedSavepoint savepoint) {
    if (savepoint == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (transaction.state() != TransactionState.ACTIVE || activeScanCount != 0) {
      return StatusCode.CONFLICT;
    }
    if (savepointCount >= savepoints.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = savepoint.claim(
        this, transaction.transactionId(), pendingMutations.count());
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
        || activeScanCount != 0
        || !savepoint.isOwnedBy(this, transaction.transactionId())
        || savepoint.pendingMutationCount() > pendingMutations.count()) {
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
    return pendingMutations.nextIndex(cursor);
  }

  private int findActiveScan(IndexedScanCursor cursor) {
    for (int index = 0; index < activeScanCount; index++) {
      if (activeScans[index] == cursor) {
        return index;
      }
    }
    return -1;
  }

  public StatusCode commit(TransactionOutcome result) {
    if (activeScanCount != 0 || statementActive) {
      return StatusCode.CONFLICT;
    }
    compactWriteSet();
    if (pendingMutations.count() == 0) {
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
        && activeScanCount == 0
        && pendingMutations.count() > 0
        && !manager.hasLockConflict(transaction)
        && !serializableScan;
  }

  Transaction groupTransaction() {
    return transaction;
  }

  PendingMutationBuffer pendingMutations() {
    return pendingMutations;
  }

  HeapInsertResult preparedInsertResult() {
    return preparedInsertResult;
  }

  void recordPreparedAppend(int rowId, long commitSequence) {
    commitResult.set(rowId, commitSequence);
    committedSequence = commitSequence;
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
    if (activeScanCount != 0 || statementActive) {
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
    StatusCode status = table.commitMutations(
        transactionId, pendingMutations, commitResult);
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
      lockedSpaces[index] = 0;
      lockedUpperSpaces[index] = 0;
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
    pendingMutations.truncate(first);
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

  private int findHeldLock(int space, long key) {
    for (int index = 0; index < heldLockCount; index++) {
      if (!rangeLocks[index]
          && lockedSpaces[index] == space
          && lockedKeys[index] == key) {
        return index;
      }
    }
    return -1;
  }

  private StatusCode acquireExclusiveKey(int space, long key) {
    int lockIndex = findHeldLock(space, key);
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
        transaction, space, key, heldLocks[heldLockCount]);
    if (status.isOk()) {
      lockedKeys[heldLockCount] = key;
      lockedSpaces[heldLockCount] = space;
      lockedUpperKeys[heldLockCount] = 0;
      exclusiveLocks[heldLockCount] = true;
      rangeLocks[heldLockCount] = false;
      heldLockCount++;
    }
    return status;
  }

  private StatusCode acquireSharedRange(
      int lowerSpace, long lowerKey, int upperSpace, long upperKey) {
    for (int index = 0; index < heldLockCount; index++) {
      if (rangeLocks[index]
          && OrderedKey.compare(
              lockedSpaces[index], lockedKeys[index], lowerSpace, lowerKey) <= 0
          && OrderedKey.compare(
              lockedUpperSpaces[index], lockedUpperKeys[index], upperSpace, upperKey) >= 0) {
        return StatusCode.OK;
      }
    }
    if (heldLockCount >= MAXIMUM_HELD_LOCKS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = manager.tryAcquireSharedRange(
        transaction, lowerSpace, lowerKey, upperSpace, upperKey,
        heldLocks[heldLockCount]);
    if (status.isOk()) {
      lockedKeys[heldLockCount] = lowerKey;
      lockedUpperKeys[heldLockCount] = upperKey;
      lockedSpaces[heldLockCount] = lowerSpace;
      lockedUpperSpaces[heldLockCount] = upperSpace;
      exclusiveLocks[heldLockCount] = false;
      rangeLocks[heldLockCount] = true;
      heldLockCount++;
    }
    return status;
  }

  private StatusCode refreshForWrite() {
    if (transaction.isolationLevel() != IsolationLevel.READ_COMMITTED
        || statementActive) {
      return StatusCode.OK;
    }
    return manager.refreshReadCommitted(transaction, table);
  }

  private boolean containsNonInsertMutation() {
    return pendingMutations.containsNonInsertMutation();
  }

  private int findLatestPendingIndex(int space, long key) {
    return pendingMutations.findLatestIndex(space, key);
  }

  private void compactWriteSet() {
    pendingMutations.compact();
  }
}
