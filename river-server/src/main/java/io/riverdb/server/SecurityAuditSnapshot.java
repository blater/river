package io.riverdb.server;

/**
 * Allocation-free-at-event-boundary counters sampled from one security-audit
 * owner. The histogram entries at indexes {@code 0..15} represent cohorts of
 * sizes {@code 1..16}; the last entry represents cohorts of size 17 or more.
 * Activity counters describe work performed since this audit owner opened.
 * {@link #durableFrontier()} is the exception: it is restored from the
 * validated durable prefix when an existing audit file is reopened.
 */
public final class SecurityAuditSnapshot {
  public static final int COHORT_HISTOGRAM_BUCKETS = 17;
  private static final SecurityAuditSnapshot EMPTY = new SecurityAuditSnapshot(
      0, 0, 0, 0, 0, new long[COHORT_HISTOGRAM_BUCKETS],
      0, 0, 0, 0, 0, 0);

  private final long decisions;
  private final long appendedBytes;
  private final long batches;
  private final long forceCalls;
  private final long forceNanos;
  private final long[] cohortHistogram;
  private final long pendingBytesHighWater;
  private final long capacityRejections;
  private final long pressureRejections;
  private final long cancellations;
  private final long fences;
  private final long durableFrontier;

  SecurityAuditSnapshot(
      long decisions,
      long appendedBytes,
      long batches,
      long forceCalls,
      long forceNanos,
      long[] cohortHistogram,
      long pendingBytesHighWater,
      long capacityRejections,
      long pressureRejections,
      long cancellations,
      long fences,
      long durableFrontier) {
    this.decisions = decisions;
    this.appendedBytes = appendedBytes;
    this.batches = batches;
    this.forceCalls = forceCalls;
    this.forceNanos = forceNanos;
    this.cohortHistogram = cohortHistogram.clone();
    this.pendingBytesHighWater = pendingBytesHighWater;
    this.capacityRejections = capacityRejections;
    this.pressureRejections = pressureRejections;
    this.cancellations = cancellations;
    this.fences = fences;
    this.durableFrontier = durableFrontier;
  }

  static SecurityAuditSnapshot empty() {
    return EMPTY;
  }

  public long decisions() {
    return decisions;
  }

  public long appendedBytes() {
    return appendedBytes;
  }

  public long batches() {
    return batches;
  }

  public long forceCalls() {
    return forceCalls;
  }

  public long forceNanos() {
    return forceNanos;
  }

  public long[] cohortHistogram() {
    return cohortHistogram.clone();
  }

  public long pendingBytesHighWater() {
    return pendingBytesHighWater;
  }

  public long capacityRejections() {
    return capacityRejections;
  }

  public long pressureRejections() {
    return pressureRejections;
  }

  public long cancellations() {
    return cancellations;
  }

  public long fences() {
    return fences;
  }

  public long durableFrontier() {
    return durableFrontier;
  }
}
