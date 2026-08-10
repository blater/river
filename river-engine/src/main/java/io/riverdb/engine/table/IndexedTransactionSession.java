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

  private final TransactionManager manager;
  private final IndexedTable table;
  private final Transaction transaction;
  private final ByteBuffer pendingRows;
  private final long[] pendingKeys = new long[MAXIMUM_PENDING_INSERTS];
  private final int[] pendingRowLengths = new int[MAXIMUM_PENDING_INSERTS];
  private final LockToken[] keyLocks = new LockToken[MAXIMUM_PENDING_INSERTS];
  private final IndexedCommitResult commitResult = new IndexedCommitResult();
  private final int rowStride;
  private long committedSequence;
  private long copiedWriteSetBytes;
  private int pendingInsertCount;

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
    if (isolationLevel == IsolationLevel.SERIALIZABLE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    pendingInsertCount = 0;
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
    LockToken keyLock = keyLocks[pendingInsertCount];
    StatusCode status = manager.tryAcquireKey(
        transaction, TABLE_LOCK_ID, key, keyLock);
    if (!status.isOk()) {
      return status;
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
      return StatusCode.INVALID_EXTERNAL_INPUT;
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
    for (int index = 0; index < pendingInsertCount; index++) {
      LockToken keyLock = keyLocks[index];
      if (!keyLock.isActive()) {
        continue;
      }
      StatusCode status = manager.release(keyLock);
      if (result.isOk() && !status.isOk()) {
        result = status;
      }
    }
    return result;
  }

  private void clearWriteSet() {
    for (int index = 0; index < pendingInsertCount; index++) {
      pendingKeys[index] = 0;
      pendingRowLengths[index] = 0;
    }
    pendingInsertCount = 0;
  }
}
