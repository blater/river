package io.riverdb.tx.api.lock;

/** Caller-owned lock request; the two resource words are opaque to the lock provider. */
public final class LockRequest {
  private LockScope scope = LockScope.ROW;
  private LockMode mode = LockMode.SHARED;
  private long resourceHigh;
  private long resourceLow;
  private long deadlineNanos;

  public LockRequest set(
      LockScope lockScope,
      long identityHigh,
      long identityLow,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = lockScope;
    resourceHigh = identityHigh;
    resourceLow = identityLow;
    mode = lockMode;
    deadlineNanos = waitDeadlineNanos;
    return this;
  }

  public LockScope scope() {
    return scope;
  }

  public LockMode mode() {
    return mode;
  }

  public long resourceHigh() {
    return resourceHigh;
  }

  public long resourceLow() {
    return resourceLow;
  }

  /** Zero means the caller chose a non-expiring wait policy. */
  public long deadlineNanos() {
    return deadlineNanos;
  }
}
