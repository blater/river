package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
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
  private static final int MAXIMUM_PENDING_INSERTS = 4;
  private static final int MAXIMUM_HELD_LOCKS = 8;

  private final TransactionManager manager;
  private final IndexedTable table;
  private final Transaction transaction;
  private final ByteBuffer pendingRows;
  private final long[] pendingKeys = new long[MAXIMUM_PENDING_INSERTS];
  private final int[] pendingRowLengths = new int[MAXIMUM_PENDING_INSERTS];
  private final LockToken[] keyLocks = new LockToken[MAXIMUM_HELD_LOCKS];
  private final long[] lockedKeys = new long[MAXIMUM_HELD_LOCKS];
  private final boolean[] exclusiveLocks = new boolean[MAXIMUM_HELD_LOCKS];
  private final IndexedCommitResult commitResult = new IndexedCommitResult();
  private final int rowStride;
  private long committedSequence;
  private long copiedWriteSetBytes;
  private int pendingInsertCount;
  private int heldLockCount;

  public IndexedTransactionSession(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      int maximumRowBytes) {
    manager = transactionManager;
    table = indexedTable;
    transaction = new Transaction(transactionManager.maximumActiveTransactions());
    rowStride = maximumRowBytes;
    pendingRows = ByteBuffer.allocateDirect(maximumRowBytes * MAXIMUM_PENDING_INSERTS);
    for (int index = 0; index < keyLocks.length; index++) {
      keyLocks[index] = new LockToken();
    }
  }

  public Transaction transaction() {
    return transaction;
  }

  public long copiedWriteSetBytes() {
    return copiedWriteSetBytes;
  }

  public StatusCode begin(IsolationLevel isolationLevel) {
    if (transaction.isActiveHandle()) {
      return StatusCode.CONFLICT;
    }
    pendingInsertCount = 0;
    heldLockCount = 0;
    committedSequence = 0;
    for (LockToken keyLock : keyLocks) {
      StatusCode status = keyLock.reset();
      if (!status.isOk()) {
        return status;
      }
    }
    return manager.begin(isolationLevel, table, transaction);
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
    for (int index = 0; index < pendingInsertCount; index++) {
      if (pendingKeys[index] == key) {
        return StatusCode.CONFLICT;
      }
    }
    int lockIndex = findHeldLock(key);
    if (lockIndex >= 0) {
      if (!exclusiveLocks[lockIndex]) {
        StatusCode status = manager.upgradeKey(transaction, keyLocks[lockIndex]);
        if (!status.isOk()) {
          return status;
        }
        exclusiveLocks[lockIndex] = true;
      }
    } else {
      if (heldLockCount >= MAXIMUM_HELD_LOCKS) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      LockToken keyLock = keyLocks[heldLockCount];
      StatusCode status = manager.tryAcquireKey(
          transaction, TABLE_LOCK_ID, key, keyLock);
      if (!status.isOk()) {
        return status;
      }
      lockedKeys[heldLockCount] = key;
      exclusiveLocks[heldLockCount] = true;
      heldLockCount++;
    }
    int sourceStart = row.position();
    int rowBytes = row.remaining();
    int destinationStart = pendingInsertCount * rowStride;
    pendingRows.limit(pendingRows.capacity());
    for (int index = 0; index < rowBytes; index++) {
      pendingRows.put(destinationStart + index, row.get(sourceStart + index));
    }
    copiedWriteSetBytes += rowBytes;
    pendingKeys[pendingInsertCount] = key;
    pendingRowLengths[pendingInsertCount] = rowBytes;
    pendingInsertCount++;
    return StatusCode.OK;
  }

  public StatusCode fetchByKey(long key, HeapRowResult result) {
    if (transaction.state() != TransactionState.ACTIVE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < pendingInsertCount; index++) {
      if (pendingKeys[index] == key) {
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
          transaction, TABLE_LOCK_ID, key, keyLocks[heldLockCount]);
      if (!status.isOk()) {
        return status;
      }
      lockedKeys[heldLockCount] = key;
      exclusiveLocks[heldLockCount] = false;
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

  public StatusCode commit(TransactionOutcome result) {
    if (pendingInsertCount == 0) {
      StatusCode status = manager.commitReadOnly(transaction, result);
      if (!status.isOk()) {
        return status;
      }
      StatusCode release = releaseLocks();
      clearWriteSet();
      return release;
    }
    StatusCode status = manager.commit(transaction, this, result);
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
    StatusCode status = table.commitInserts(
        transactionId,
        pendingKeys,
        pendingRows,
        rowStride,
        pendingRowLengths,
        pendingInsertCount,
        commitResult);
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
      LockToken keyLock = keyLocks[index];
      if (!keyLock.isActive()) {
        continue;
      }
      StatusCode status = manager.release(keyLock);
      if (result.isOk() && !status.isOk()) {
        result = status;
      }
      lockedKeys[index] = 0;
      exclusiveLocks[index] = false;
    }
    heldLockCount = 0;
    return result;
  }

  private void clearWriteSet() {
    for (int index = 0; index < pendingInsertCount; index++) {
      pendingKeys[index] = 0;
      pendingRowLengths[index] = 0;
    }
    pendingInsertCount = 0;
  }

  private int findHeldLock(long key) {
    for (int index = 0; index < heldLockCount; index++) {
      if (lockedKeys[index] == key) {
        return index;
      }
    }
    return -1;
  }
}
