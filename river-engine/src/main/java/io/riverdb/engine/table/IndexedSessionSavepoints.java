package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.api.TransactionState;

/** Owns the bounded savepoint stack for one transaction session. */
final class IndexedSessionSavepoints {
  private static final int MAXIMUM_SAVEPOINTS = 4;

  private final IndexedTransactionSession owner;
  private final Transaction transaction;
  private final PendingMutationBuffer mutations;
  private final IndexedTupleIntentJournal tupleIntents;
  private final IndexedTupleIndexLifecycleBatch tupleLifecycle;
  private final IndexedSavepoint[] savepoints = new IndexedSavepoint[MAXIMUM_SAVEPOINTS];
  private int count;

  IndexedSessionSavepoints(
      IndexedTransactionSession session,
      Transaction transactionState,
      PendingMutationBuffer pending,
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle) {
    owner = session;
    transaction = transactionState;
    mutations = pending;
    tupleIntents = intents;
    tupleLifecycle = lifecycle;
  }

  boolean active() { return count != 0; }

  StatusCode create(IndexedSavepoint savepoint, boolean activeScans) {
    if (savepoint == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (transaction.state() != TransactionState.ACTIVE || activeScans) {
      return StatusCode.CONFLICT;
    }
    if (count >= savepoints.length) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = savepoint.claim(
        owner, transaction.transactionId(), mutations.count(),
        tupleIntents.mutationCount(), tupleIntents.descriptorCount(),
        tupleIntents.payloadBytes(), tupleLifecycle.count());
    if (status.isOk()) savepoints[count++] = savepoint;
    return status;
  }

  StatusCode rollback(IndexedSavepoint savepoint, boolean activeScans) {
    if (savepoint == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!validRollback(savepoint, activeScans)) return StatusCode.CONFLICT;
    int index = find(savepoint);
    if (index < 0) return StatusCode.NOT_OWNER;
    mutations.truncate(savepoint.pendingMutationCount());
    tupleIntents.truncate(
        savepoint.tupleMutationCount(), savepoint.tupleDescriptorCount(),
        savepoint.tuplePayloadBytes());
    tupleLifecycle.truncate(savepoint.tupleLifecycleCount());
    completeAfter(index + 1);
    return StatusCode.OK;
  }

  StatusCode release(IndexedSavepoint savepoint) {
    if (savepoint == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!savepoint.isOwnedBy(owner, transaction.transactionId())) {
      return StatusCode.NOT_OWNER;
    }
    int index = find(savepoint);
    if (index < 0) return StatusCode.NOT_OWNER;
    completeAfter(index);
    return StatusCode.OK;
  }

  void clear() { completeAfter(0); }

  private boolean validRollback(IndexedSavepoint savepoint, boolean activeScans) {
    return transaction.state() == TransactionState.ACTIVE
        && !activeScans
        && savepoint.isOwnedBy(owner, transaction.transactionId())
        && savepoint.pendingMutationCount() <= mutations.count()
        && savepoint.tupleMutationCount() <= tupleIntents.mutationCount()
        && savepoint.tupleDescriptorCount() <= tupleIntents.descriptorCount()
        && savepoint.tuplePayloadBytes() <= tupleIntents.payloadBytes()
        && savepoint.tupleLifecycleCount() <= tupleLifecycle.count();
  }

  private int find(IndexedSavepoint savepoint) {
    for (int index = count - 1; index >= 0; index--) {
      if (savepoints[index] == savepoint) return index;
    }
    return -1;
  }

  private void completeAfter(int first) {
    for (int index = count - 1; index >= first; index--) {
      savepoints[index].complete();
      savepoints[index] = null;
    }
    count = first;
  }
}
