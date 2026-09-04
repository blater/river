package io.riverdb.engine.table;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.Transaction;
import io.riverdb.tx.api.TransactionState;
import java.util.Arrays;

/** Owns the bounded savepoint stack for one transaction session. */
final class IndexedSessionSavepoints {
  private final IndexedTransactionSession owner;
  private final Transaction transaction;
  private final PendingMutationBuffer mutations;
  private final IndexedTupleIntentJournal tupleIntents;
  private final IndexedTupleIndexLifecycleBatch tupleLifecycle;
  private IndexedSavepoint[] savepoints = new IndexedSavepoint[0];
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
    StatusCode status = reserve();
    if (!status.isOk()) return status;
    status = savepoint.claim(
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

  private StatusCode reserve() {
    if (count < savepoints.length) return StatusCode.OK;
    if (count == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int capacity = BoundedArrayGrowth.capacity(
        savepoints.length, count + 1, Integer.MAX_VALUE, 4);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      savepoints = Arrays.copyOf(savepoints, capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

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
