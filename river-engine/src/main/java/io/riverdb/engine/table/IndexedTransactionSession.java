package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.TransactionCommitParticipant;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import java.nio.ByteBuffer;

/** One reusable transaction/session write set over the first indexed table. */
public final class IndexedTransactionSession implements TransactionCommitParticipant {
  private final TransactionManager manager;
  private final IndexedTable table;
  private final Transaction transaction;
  private final ByteBuffer pendingRow;
  private final IndexedCommitResult commitResult = new IndexedCommitResult();
  private long pendingKey;
  private long committedSequence;
  private long copiedWriteSetBytes;
  private int pendingRowBytes;
  private boolean pendingInsert;

  public IndexedTransactionSession(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      int maximumRowBytes) {
    manager = transactionManager;
    table = indexedTable;
    transaction = new Transaction(transactionManager.maximumActiveTransactions());
    pendingRow = ByteBuffer.allocateDirect(maximumRowBytes);
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
    pendingInsert = false;
    pendingRowBytes = 0;
    committedSequence = 0;
    return manager.begin(isolationLevel, table, transaction);
  }

  public StatusCode insert(long key, ByteBuffer row) {
    if (transaction.state() != TransactionState.ACTIVE
        || pendingInsert
        || key == Long.MAX_VALUE
        || row == null
        || !row.hasRemaining()
        || row.remaining() > pendingRow.capacity()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int sourceStart = row.position();
    pendingRowBytes = row.remaining();
    for (int index = 0; index < pendingRowBytes; index++) {
      pendingRow.put(index, row.get(sourceStart + index));
    }
    copiedWriteSetBytes += pendingRowBytes;
    pendingKey = key;
    pendingInsert = true;
    return StatusCode.OK;
  }

  public StatusCode fetchByKey(long key, HeapRowResult result) {
    if (transaction.state() != TransactionState.ACTIVE || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pendingInsert && pendingKey == key) {
      result.set(pendingRow, 0, 0, pendingRowBytes);
      return StatusCode.OK;
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
    if (!pendingInsert) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = manager.commit(transaction, this, result);
    if (!transaction.isActiveHandle()) {
      pendingInsert = false;
      pendingRowBytes = 0;
    }
    return status;
  }

  public StatusCode abort(TransactionOutcome result) {
    StatusCode status = manager.abort(transaction, result);
    if (status.isOk()) {
      pendingInsert = false;
      pendingRowBytes = 0;
    }
    return status;
  }

  @Override
  public StatusCode commit(long transactionId) {
    pendingRow.position(0);
    pendingRow.limit(pendingRowBytes);
    StatusCode status = table.commitInsert(
        transactionId, pendingKey, pendingRow, commitResult);
    committedSequence = status.isOk() ? commitResult.commitSequence() : 0;
    return status;
  }

  @Override
  public long committedSequence() {
    return committedSequence;
  }
}
