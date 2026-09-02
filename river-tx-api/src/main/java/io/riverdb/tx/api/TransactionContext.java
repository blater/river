package io.riverdb.tx.api;

import io.riverdb.base.concurrent.CancellationToken;
import io.riverdb.base.error.StatusCode;

/**
 * Reusable operation-facing transaction context. The snapshot and cancellation token are
 * borrowed and remain owned by the transaction implementation for this context's lifetime.
 */
public final class TransactionContext {
  private final Object editorAuthority;
  private final Snapshot snapshot;
  private final CancellationToken cancellation;
  private Object providerAuthority;
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long transactionId;
  private long transactionGeneration;
  private long transactionStartOrder;
  private IsolationLevel isolationLevel = IsolationLevel.READ_COMMITTED;
  private volatile boolean active;

  public TransactionContext(
      Object editor,
      Snapshot visibilitySnapshot,
      CancellationToken cancellationToken) {
    if (editor == null || visibilitySnapshot == null || cancellationToken == null) {
      throw new IllegalArgumentException("invalid transaction context owner");
    }
    editorAuthority = editor;
    snapshot = visibilitySnapshot;
    cancellation = cancellationToken;
  }

  /** Binds the reusable carrier; only its creating transaction knows {@code editor}. */
  public StatusCode bind(
      Object editor,
      Object provider,
      long databaseHigh,
      long databaseLow,
      long id,
      long generation,
      long startOrder,
      IsolationLevel isolation) {
    if (editorAuthority != editor) return StatusCode.NOT_OWNER;
    if (active) return StatusCode.CONFLICT;
    if (provider == null || id <= 0 || generation <= 0 || startOrder <= 0
        || isolation == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    providerAuthority = provider;
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    transactionId = id;
    transactionGeneration = generation;
    transactionStartOrder = startOrder;
    isolationLevel = isolation;
    active = true;
    return StatusCode.OK;
  }

  /** Retires exactly the currently bound generation without erasing its diagnostic identity. */
  public StatusCode complete(
      Object editor, Object provider, long id, long generation) {
    if (editorAuthority != editor || !active || providerAuthority != provider
        || transactionId != id || transactionGeneration != generation) {
      return StatusCode.NOT_OWNER;
    }
    providerAuthority = null;
    active = false;
    return StatusCode.OK;
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

  public long transactionGeneration() { return transactionGeneration; }

  public long transactionStartOrder() { return transactionStartOrder; }

  /** Provider authentication hook; the opaque authority itself is never exposed. */
  public boolean isAuthorizedBy(Object authority, long expectedGeneration) {
    return active && providerAuthority != null && providerAuthority == authority
        && transactionGeneration == expectedGeneration;
  }

  public boolean isActive() { return active; }

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
