package io.riverdb.tx;

import io.riverdb.base.concurrent.MutableCancellationToken;
import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.TransactionState;

/** Caller-owned transaction lifecycle and snapshot state. */
public final class Transaction {
  private final TransactionSnapshot snapshot;
  private final MutableCancellationToken cancellation = new MutableCancellationToken();
  private final Object contextEditor = new Object();
  private final TransactionContext context;
  private TransactionManager owner;
  private long transactionId;
  private long transactionGeneration;
  private long transactionStartOrder;
  private long commitSequence;
  private IsolationLevel isolationLevel = IsolationLevel.READ_COMMITTED;
  private TransactionState state = TransactionState.ABORTED;
  private boolean activeHandle;

  public Transaction(int maximumActiveTransactions) {
    snapshot = new TransactionSnapshot(maximumActiveTransactions);
    context = new TransactionContext(contextEditor, snapshot, cancellation);
  }

  public long transactionId() {
    return transactionId;
  }

  public long transactionGeneration() { return transactionGeneration; }

  public long transactionStartOrder() { return transactionStartOrder; }

  /** Borrowed operation context; valid only while this transaction handle is active. */
  public TransactionContext context() { return context; }

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
    transactionStartOrder = 0;
    commitSequence = 0;
    isolationLevel = IsolationLevel.READ_COMMITTED;
    state = TransactionState.ABORTED;
    cancellation.reset();
    return StatusCode.OK;
  }

  StatusCode prepareClaim(
      TransactionManager manager, long id, long startOrder, IsolationLevel isolation) {
    if (activeHandle) return StatusCode.CONFLICT;
    if (transactionGeneration == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    long nextGeneration = transactionGeneration + 1;
    owner = manager;
    transactionId = id;
    transactionGeneration = nextGeneration;
    transactionStartOrder = startOrder;
    commitSequence = 0;
    isolationLevel = isolation;
    state = TransactionState.ACTIVE;
    activeHandle = true;
    cancellation.reset();
    return StatusCode.OK;
  }

  StatusCode activateContext(
      Object providerAuthority, long databaseHigh, long databaseLow) {
    return context.bind(
        contextEditor, providerAuthority, databaseHigh, databaseLow,
        transactionId, transactionGeneration, transactionStartOrder, isolationLevel);
  }

  StatusCode freezeContext(Object providerAuthority) {
    return context.complete(
        contextEditor, providerAuthority, transactionId, transactionGeneration);
  }

  boolean contextMatches(Object providerAuthority) {
    return context.isAuthorizedBy(providerAuthority, transactionGeneration);
  }

  void abandonClaim() {
    owner = null;
    transactionId = transactionStartOrder = commitSequence = 0;
    isolationLevel = IsolationLevel.READ_COMMITTED;
    state = TransactionState.ABORTED;
    activeHandle = false;
    cancellation.reset();
  }

  boolean isOwnedBy(TransactionManager manager) {
    return activeHandle && owner == manager;
  }

  boolean isOwnedIdentityBy(TransactionManager manager) {
    return owner == manager && transactionId > 0 && transactionGeneration > 0;
  }

  void transition(TransactionState nextState, long committedAt, boolean terminal) {
    state = nextState;
    commitSequence = committedAt;
    if (terminal) {
      activeHandle = false;
    }
  }
}
