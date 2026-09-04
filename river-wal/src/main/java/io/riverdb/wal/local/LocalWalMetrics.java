package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Caller-owned, allocation-free snapshot of local WAL force telemetry. */
public final class LocalWalMetrics {
  public static final int LATENCY_BUCKETS = 64;
  private static final int CAUSE_COUNT = LocalWalForceCause.values().length;
  private static final int STATUS_COUNT = StatusCode.values().length;

  private final long[] forceCounts = new long[CAUSE_COUNT];
  private final long[] forceBytes = new long[CAUSE_COUNT];
  private final long[] forceNanos = new long[CAUSE_COUNT];
  private final long[] forceStatusCounts = new long[CAUSE_COUNT * STATUS_COUNT];
  private final long[] forceLatencyCounts = new long[CAUSE_COUNT * LATENCY_BUCKETS];
  private long totalForceCount;
  private long totalForceBytes;
  private long totalForceNanos;
  private boolean overflowed;

  public void reset() {
    java.util.Arrays.fill(forceCounts, 0);
    java.util.Arrays.fill(forceBytes, 0);
    java.util.Arrays.fill(forceNanos, 0);
    java.util.Arrays.fill(forceStatusCounts, 0);
    java.util.Arrays.fill(forceLatencyCounts, 0);
    totalForceCount = 0;
    totalForceBytes = 0;
    totalForceNanos = 0;
    overflowed = false;
  }

  public long totalForceCount() {
    return totalForceCount;
  }

  public long totalForceBytes() {
    return totalForceBytes;
  }

  public long totalForceNanos() {
    return totalForceNanos;
  }

  public boolean overflowed() { return overflowed; }

  public long forceCount(LocalWalForceCause cause) {
    return forceCounts[cause.ordinal()];
  }

  public long forceBytes(LocalWalForceCause cause) {
    return forceBytes[cause.ordinal()];
  }

  public long forceNanos(LocalWalForceCause cause) {
    return forceNanos[cause.ordinal()];
  }

  public long forceStatusCount(LocalWalForceCause cause, StatusCode status) {
    return forceStatusCounts[statusIndex(cause, status)];
  }

  public long forceLatencyBucket(LocalWalForceCause cause, int bucket) {
    if (bucket < 0 || bucket >= LATENCY_BUCKETS) return 0;
    return forceLatencyCounts[cause.ordinal() * LATENCY_BUCKETS + bucket];
  }

  /** Returns whether cause and status totals describe the same force population. */
  public boolean reconciles() {
    long count = 0;
    long bytes = 0;
    long nanos = 0;
    for (LocalWalForceCause cause : LocalWalForceCause.values()) {
      count = sum(count, forceCounts[cause.ordinal()]);
      bytes = sum(bytes, forceBytes[cause.ordinal()]);
      nanos = sum(nanos, forceNanos[cause.ordinal()]);
      long statuses = 0;
      for (StatusCode status : StatusCode.values()) {
        statuses = sum(statuses, forceStatusCount(cause, status));
      }
      if (statuses != forceCount(cause)) return false;
    }
    return !overflowed
        && count == totalForceCount
        && bytes == totalForceBytes
        && nanos == totalForceNanos;
  }

  void record(
      LocalWalForceCause cause,
      long coveredBytes,
      long elapsedNanos,
      StatusCode status) {
    int causeIndex = cause.ordinal();
    long bytesValue = Math.max(0, coveredBytes);
    long nanosValue = Math.max(0, elapsedNanos);
    forceCounts[causeIndex] = increment(forceCounts[causeIndex]);
    forceBytes[causeIndex] = add(forceBytes[causeIndex], bytesValue);
    forceNanos[causeIndex] = add(forceNanos[causeIndex], nanosValue);
    totalForceCount = increment(totalForceCount);
    totalForceBytes = add(totalForceBytes, bytesValue);
    totalForceNanos = add(totalForceNanos, nanosValue);
    int statusIndex = statusIndex(cause, status);
    forceStatusCounts[statusIndex] = increment(forceStatusCounts[statusIndex]);
    int bucket = latencyBucket(elapsedNanos);
    int latencyIndex = causeIndex * LATENCY_BUCKETS + bucket;
    forceLatencyCounts[latencyIndex] = increment(forceLatencyCounts[latencyIndex]);
  }

  void copyFrom(LocalWalMetrics source) {
    totalForceCount = source.totalForceCount;
    totalForceBytes = source.totalForceBytes;
    totalForceNanos = source.totalForceNanos;
    overflowed = source.overflowed;
    System.arraycopy(source.forceCounts, 0, forceCounts, 0, forceCounts.length);
    System.arraycopy(source.forceBytes, 0, forceBytes, 0, forceBytes.length);
    System.arraycopy(source.forceNanos, 0, forceNanos, 0, forceNanos.length);
    System.arraycopy(
        source.forceStatusCounts, 0, forceStatusCounts, 0, forceStatusCounts.length);
    System.arraycopy(
        source.forceLatencyCounts, 0, forceLatencyCounts, 0, forceLatencyCounts.length);
  }

  void merge(LocalWalMetrics source) {
    for (int index = 0; index < forceCounts.length; index++) {
      forceCounts[index] = add(forceCounts[index], source.forceCounts[index]);
      forceBytes[index] = add(forceBytes[index], source.forceBytes[index]);
      forceNanos[index] = add(forceNanos[index], source.forceNanos[index]);
    }
    for (int index = 0; index < forceStatusCounts.length; index++) {
      forceStatusCounts[index] = add(
          forceStatusCounts[index], source.forceStatusCounts[index]);
    }
    for (int index = 0; index < forceLatencyCounts.length; index++) {
      forceLatencyCounts[index] = add(
          forceLatencyCounts[index], source.forceLatencyCounts[index]);
    }
    totalForceCount = add(totalForceCount, source.totalForceCount);
    totalForceBytes = add(totalForceBytes, source.totalForceBytes);
    totalForceNanos = add(totalForceNanos, source.totalForceNanos);
    overflowed |= source.overflowed;
  }

  private static int statusIndex(LocalWalForceCause cause, StatusCode status) {
    return cause.ordinal() * STATUS_COUNT + status.ordinal();
  }

  private static int latencyBucket(long elapsedNanos) {
    if (elapsedNanos <= 1) return 0;
    return Math.min(LATENCY_BUCKETS - 1, 63 - Long.numberOfLeadingZeros(elapsedNanos));
  }

  private long increment(long value) {
    if (value == Long.MAX_VALUE) {
      overflowed = true;
      return value;
    }
    return value + 1;
  }

  private long add(long current, long value) {
    if (value < 0 || Long.MAX_VALUE - current < value) {
      overflowed = true;
      return Long.MAX_VALUE;
    }
    return current + value;
  }

  private static long sum(long left, long right) {
    return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
  }
}
