package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionState;

/** Owns active snapshot registration, capture sequencing, refresh, and reclamation floor. */
final class TransactionSnapshotLifecycle {
  private final TransactionSnapshotRegistry registry;
  private final long databaseHigh;
  private final long databaseLow;
  private long nextSequence = 1;

  TransactionSnapshotLifecycle(long high, long low, int capacity) {
    databaseHigh = high;
    databaseLow = low;
    registry = new TransactionSnapshotRegistry(capacity);
  }

  int capacity() { return registry.capacity(); }
  int count() { return registry.count(); }
  boolean full() { return registry.full(); }
  long oldestVisibleCommitSequence() { return registry.oldestVisibleCommitSequence(); }
  void remove(long transactionId) { registry.remove(transactionId); }

  StatusCode admit(Transaction transaction, long visibleCommitSequence) {
    capture(transaction, visibleCommitSequence);
    return registry.admit(transaction.transactionId(), visibleCommitSequence);
  }

  StatusCode refresh(
      TransactionManager owner,
      LockManager locks,
      Transaction transaction,
      IsolationLevel requiredIsolation,
      long visibleCommitSequence) {
    if (transaction == null
        || !transaction.isOwnedBy(owner)
        || transaction.state() != TransactionState.ACTIVE
        || transaction.isolationLevel() != requiredIsolation
        || visibleCommitSequence < transaction.snapshot().visibleCommitSequence()) {
      return StatusCode.CONFLICT;
    }
    if (locks.exact.deadlocked(
            transaction.transactionId(), transaction.transactionGeneration())
        || locks.exact.lifecycle.hasPendingRequests(
            transaction.transactionId(), transaction.transactionGeneration())) {
      return StatusCode.CONFLICT;
    }
    capture(transaction, visibleCommitSequence);
    registry.update(transaction.transactionId(), visibleCommitSequence);
    return StatusCode.OK;
  }

  private void capture(Transaction transaction, long visibleCommitSequence) {
    transaction.snapshot().capture(
        databaseHigh,
        databaseLow,
        nextSequence++,
        visibleCommitSequence,
        registry.transactionIds(),
        registry.count());
  }
}
