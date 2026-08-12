package io.riverdb.tx;

import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionState;

/** Caller-owned transaction lifecycle and snapshot state. */
public final class Transaction {
  private final TransactionSnapshot snapshot;
  private final MutableCancellationToken cancellation = new MutableCancellationToken();
  private TransactionManager owner;
  private long transactionId;
  private long commitSequence;
  private IsolationLevel isolationLevel = IsolationLevel.READ_COMMITTED;
  private TransactionState state = TransactionState.ABORTED;
  private boolean activeHandle;

  public Transaction(int maximumActiveTransactions) {
    snapshot = new TransactionSnapshot(maximumActiveTransactions);
  }

  public long transactionId() {
    return transactionId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public IsolationLevel isolationLevel() {
    return isolationLevel;
  }

  public TransactionState state() {
    return state;
  }

  public TransactionSnapshot snapshot() {
    return snapshot;
  }

  public MutableCancellationToken cancellation() {
    return cancellation;
  }

  public boolean isActiveHandle() {
    return activeHandle;
  }

  public StatusCode reset() {
    if (activeHandle) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    transactionId = 0;
    commitSequence = 0;
    isolationLevel = IsolationLevel.READ_COMMITTED;
    state = TransactionState.ABORTED;
    cancellation.reset();
    return StatusCode.OK;
  }

  StatusCode claim(
      TransactionManager manager,
      long id,
      IsolationLevel isolation) {
    if (activeHandle) {
      return StatusCode.CONFLICT;
    }
    owner = manager;
    transactionId = id;
    commitSequence = 0;
    isolationLevel = isolation;
    state = TransactionState.ACTIVE;
    activeHandle = true;
    cancellation.reset();
    return StatusCode.OK;
  }

  boolean isOwnedBy(TransactionManager manager) {
    return activeHandle && owner == manager;
  }

  void transition(TransactionState nextState, long committedAt, boolean terminal) {
    state = nextState;
    commitSequence = committedAt;
    if (terminal) {
      activeHandle = false;
    }
  }
}
