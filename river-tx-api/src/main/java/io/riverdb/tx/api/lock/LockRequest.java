package io.riverdb.tx.api.lock;

import java.nio.ByteBuffer;

/** Caller-owned lock request with exact generic identities or ordered-key endpoints. */
public final class LockRequest {
  private LockScope scope = LockScope.ROW;
  private LockMode mode = LockMode.SHARED;
  private long lowerSpace;
  private long lowerKey;
  private long upperSpace;
  private long upperKey;
  private ByteBuffer tupleLower;
  private ByteBuffer tupleUpper;
  private int tupleLowerOffset;
  private int tupleLowerLength;
  private int tupleUpperOffset;
  private int tupleUpperLength;
  private boolean tupleLowerInclusive;
  private boolean tupleUpperInclusive;
  private long deadlineNanos;
  private boolean hasDeadline;

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
    clearTuple();
    mode = lockMode;
    setDeadline(waitDeadlineNanos);
    return this;
  }

  /** Sets one exact ordered key identified by its physical key space and signed scalar. */
  public LockRequest setKey(
      long space,
      long key,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = LockScope.KEY;
    lowerSpace = space;
    lowerKey = key;
    upperSpace = space;
    upperKey = key;
    clearTuple();
    mode = lockMode;
    setDeadline(waitDeadlineNanos);
    return this;
  }

  /** Sets one half-open ordered interval {@code [lower, upper)}. */
  public LockRequest setRange(
      long intervalLowerSpace,
      long intervalLowerKey,
      long intervalUpperSpace,
      long intervalUpperKey,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = LockScope.RANGE;
    lowerSpace = intervalLowerSpace;
    lowerKey = intervalLowerKey;
    upperSpace = intervalUpperSpace;
    upperKey = intervalUpperKey;
    clearTuple();
    mode = lockMode;
    setDeadline(waitDeadlineNanos);
    return this;
  }

  /**
   * Sets one exact canonical user tuple. The caller excludes tuple headers and physical row-id
   * suffixes. The buffer is borrowed and must remain immutable until the lock-service call returns;
   * the provider copies it only when creating a canonical resource.
   */
  public LockRequest setTupleKey(
      long namespace,
      ByteBuffer key,
      int offset,
      int length,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = LockScope.TUPLE_KEY;
    lowerSpace = namespace;
    lowerKey = upperSpace = upperKey = 0;
    tupleLower = key;
    tupleLowerOffset = offset;
    tupleLowerLength = length;
    tupleUpper = null;
    tupleUpperOffset = tupleUpperLength = 0;
    tupleLowerInclusive = tupleUpperInclusive = true;
    mode = lockMode;
    setDeadline(waitDeadlineNanos);
    return this;
  }

  /**
   * Sets a prefix-aware tuple interval. A null bound is namespace infinity. An inclusive lower
   * bound sits before its prefix subtree and an exclusive lower bound after it. An exclusive upper
   * bound sits before its prefix subtree and an inclusive upper bound after it. Thus equal
   * inclusive bounds cover one logical tuple and all its physical suffixes. Both buffers are
   * borrowed and must remain immutable until the lock-service call returns.
   */
  public LockRequest setTupleRange(
      long namespace,
      ByteBuffer lower,
      int lowerOffset,
      int lowerLength,
      boolean lowerInclusive,
      ByteBuffer upper,
      int upperOffset,
      int upperLength,
      boolean upperInclusive,
      LockMode lockMode,
      long waitDeadlineNanos) {
    scope = LockScope.TUPLE_RANGE;
    lowerSpace = namespace;
    lowerKey = upperSpace = upperKey = 0;
    tupleLower = lower;
    tupleLowerOffset = lowerOffset;
    tupleLowerLength = lowerLength;
    tupleUpper = upper;
    tupleUpperOffset = upperOffset;
    tupleUpperLength = upperLength;
    tupleLowerInclusive = lowerInclusive;
    tupleUpperInclusive = upperInclusive;
    mode = lockMode;
    setDeadline(waitDeadlineNanos);
    return this;
  }

  public LockScope scope() {
    return scope;
  }

  public LockMode mode() {
    return mode;
  }

  public long lowerSpace() {
    return lowerSpace;
  }

  public long lowerKey() {
    return lowerKey;
  }

  public long upperSpace() {
    return upperSpace;
  }

  public long upperKey() {
    return upperKey;
  }

  public long tupleNamespace() { return lowerSpace; }

  public ByteBuffer tupleLower() { return tupleLower; }

  public int tupleLowerOffset() { return tupleLowerOffset; }

  public int tupleLowerLength() { return tupleLowerLength; }

  public boolean tupleLowerInclusive() { return tupleLowerInclusive; }

  public ByteBuffer tupleUpper() { return tupleUpper; }

  public int tupleUpperOffset() { return tupleUpperOffset; }

  public int tupleUpperLength() { return tupleUpperLength; }

  public boolean tupleUpperInclusive() { return tupleUpperInclusive; }

  /** Selects a finite absolute monotonic deadline, including an exact wrapped value of zero. */
  public LockRequest waitUntil(long absoluteNanoTime) {
    deadlineNanos = absoluteNanoTime;
    hasDeadline = true;
    return this;
  }

  public boolean hasDeadline() { return hasDeadline; }

  public long deadlineNanos() {
    return deadlineNanos;
  }

  private void setDeadline(long waitDeadlineNanos) {
    deadlineNanos = waitDeadlineNanos;
    hasDeadline = waitDeadlineNanos != 0;
  }

  private void clearTuple() {
    tupleLower = tupleUpper = null;
    tupleLowerOffset = tupleLowerLength = tupleUpperOffset = tupleUpperLength = 0;
    tupleLowerInclusive = tupleUpperInclusive = false;
  }
}
