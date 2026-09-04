package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockScope;

/** Caller-owned aggregate timings for one successful commit-group completion barrier. */
public final class TransactionGroupCompletionTimings {
  private static final LockScope[] LOCK_SCOPES = LockScope.values();

  private final long[] lockHoldingsReleasedByScope = new long[LOCK_SCOPES.length];
  private long lockReleaseNanos;
  private long lockOutcomeNanos;
  private long lockRequestCancellationNanos;
  private long lockHoldingReleaseNanos;
  private long lockRecordRecycleNanos;
  private long lockHoldingsReleased;
  private long activeRemovalNanos;
  private long outcomePublicationNanos;

  public void reset() {
    lockReleaseNanos = 0;
    lockOutcomeNanos = 0;
    lockRequestCancellationNanos = 0;
    lockHoldingReleaseNanos = 0;
    lockRecordRecycleNanos = 0;
    lockHoldingsReleased = 0;
    java.util.Arrays.fill(lockHoldingsReleasedByScope, 0);
    activeRemovalNanos = 0;
    outcomePublicationNanos = 0;
  }

  public long lockReleaseNanos() { return lockReleaseNanos; }

  public long lockOutcomeNanos() { return lockOutcomeNanos; }

  public long lockRequestCancellationNanos() { return lockRequestCancellationNanos; }

  public long lockHoldingReleaseNanos() { return lockHoldingReleaseNanos; }

  public long lockRecordRecycleNanos() { return lockRecordRecycleNanos; }

  public long lockHoldingsReleased() { return lockHoldingsReleased; }

  public long lockHoldingsReleased(LockScope scope) {
    return scope == null ? 0 : lockHoldingsReleasedByScope[scope.ordinal()];
  }

  public long activeRemovalNanos() { return activeRemovalNanos; }

  public long outcomePublicationNanos() { return outcomePublicationNanos; }

  void set(long release, long removal, long publication) {
    lockReleaseNanos = release;
    activeRemovalNanos = removal;
    outcomePublicationNanos = publication;
  }

  void addLockReleasePhases(
      long outcome,
      long requestCancellation,
      long holdingRelease,
      long recordRecycle) {
    lockOutcomeNanos = add(lockOutcomeNanos, outcome);
    lockRequestCancellationNanos = add(
        lockRequestCancellationNanos, requestCancellation);
    lockHoldingReleaseNanos = add(lockHoldingReleaseNanos, holdingRelease);
    lockRecordRecycleNanos = add(lockRecordRecycleNanos, recordRecycle);
  }

  void releasedLockHolding(byte scope) {
    if (scope < 0 || scope >= lockHoldingsReleasedByScope.length) return;
    lockHoldingsReleased = add(lockHoldingsReleased, 1);
    lockHoldingsReleasedByScope[scope] = add(
        lockHoldingsReleasedByScope[scope], 1);
  }

  private static long add(long left, long right) {
    return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
  }
}
