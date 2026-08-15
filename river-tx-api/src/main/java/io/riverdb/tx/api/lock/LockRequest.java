package io.riverdb.tx.api.lock;

/** Caller-owned lock request with exact generic identities or ordered-key endpoints. */
public final class LockRequest {
  private LockScope scope = LockScope.ROW;
  private LockMode mode = LockMode.SHARED;
  private int lowerSpace;
  private long lowerKey;
  private int upperSpace;
  private long upperKey;
  private long deadlineNanos;

  /** Sets one exact non-key resource identity. */
  public LockRequest setExact(
      LockScope lockScope,
      long identityHigh,
      long identityLow,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = lockScope;
    boolean nonKey = lockScope != LockScope.KEY && lockScope != LockScope.RANGE;
    lowerSpace = nonKey ? 0 : -1;
    lowerKey = identityHigh;
    upperSpace = nonKey ? 0 : -1;
    upperKey = identityLow;
    mode = lockMode;
    deadlineNanos = waitDeadlineNanos;
    return this;
  }

  /** Sets one exact ordered key identified by its physical key space and signed scalar. */
  public LockRequest setKey(
      int space,
      long key,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = LockScope.KEY;
    lowerSpace = space;
    lowerKey = key;
    upperSpace = space;
    upperKey = key;
    mode = lockMode;
    deadlineNanos = waitDeadlineNanos;
    return this;
  }

  /** Sets one half-open ordered interval {@code [lower, upper)}. */
  public LockRequest setRange(
      int intervalLowerSpace,
      long intervalLowerKey,
      int intervalUpperSpace,
      long intervalUpperKey,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = LockScope.RANGE;
    lowerSpace = intervalLowerSpace;
    lowerKey = intervalLowerKey;
    upperSpace = intervalUpperSpace;
    upperKey = intervalUpperKey;
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

  public int lowerSpace() {
    return lowerSpace;
  }

  public long lowerKey() {
    return lowerKey;
  }

  public int upperSpace() {
    return upperSpace;
  }

  public long upperKey() {
    return upperKey;
  }

  /** Zero means the caller chose a non-expiring wait policy. */
  public long deadlineNanos() {
    return deadlineNanos;
  }
}
