package io.riverdb.tx.api;

import io.riverdb.base.concurrent.CancellationToken;

/**
 * Immutable operation-facing transaction context. The snapshot and cancellation token are
 * borrowed and remain owned by the transaction implementation for this context's lifetime.
 */
public final class TransactionContext {
  private final long databaseIncarnationHigh;
  private final long databaseIncarnationLow;
  private final long transactionId;
  private final IsolationLevel isolationLevel;
  private final Snapshot snapshot;
  private final CancellationToken cancellation;

  public TransactionContext(
      long databaseHigh,
      long databaseLow,
      long id,
      IsolationLevel isolation,
      Snapshot visibilitySnapshot,
      CancellationToken cancellationToken) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    transactionId = id;
    isolationLevel = isolation;
    snapshot = visibilitySnapshot;
    cancellation = cancellationToken;
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

  public IsolationLevel isolationLevel() {
    return isolationLevel;
  }

  public Snapshot snapshot() {
    return snapshot;
  }

  public CancellationToken cancellation() {
    return cancellation;
  }
}
