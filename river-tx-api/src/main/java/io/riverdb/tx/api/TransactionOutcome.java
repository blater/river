package io.riverdb.tx.api;

/** Caller-owned transaction-status output populated by a transaction state provider. */
public final class TransactionOutcome {
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long transactionId;
  private long commitSequence;
  private TransactionState state = TransactionState.ACTIVE;
  private boolean available;

  public TransactionOutcome reset() {
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    transactionId = 0;
    commitSequence = 0;
    state = TransactionState.ACTIVE;
    available = false;
    return this;
  }

  /** Provider population hook; callers treat the populated carrier as immutable until reuse. */
  public TransactionOutcome set(
      long databaseHigh,
      long databaseLow,
      long id,
      TransactionState transactionState,
      long committedAt) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    transactionId = id;
    state = transactionState;
    commitSequence = committedAt;
    available = true;
    return this;
  }

  public long databaseIncarnationHigh() {
    return databaseIncarnationHigh;
  }

  public long databaseIncarnationLow() {
    return databaseIncarnationLow;
  }

  public long transactionId() {
    return transactionId;
  }

  public TransactionState state() {
    return state;
  }

  /**
   * Reported commit or snapshot position. A read-only snapshot position can include unrelated
   * pending publications; successful completion makes the observed dependencies durable, not
   * every write through that position. This value is not a durable-prefix watermark.
   */
  public long commitSequence() {
    return commitSequence;
  }

  public boolean isAvailable() {
    return available;
  }

  public boolean isFinal() {
    return state == TransactionState.COMMITTED || state == TransactionState.ABORTED;
  }
}
