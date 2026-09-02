package io.riverdb.engine.table;

/** Exact database-lifetime counters for reactive WAL commit batching. */
final class IndexedGroupCommitMetrics {
  volatile long cohorts;
  volatile long submitted;
  volatile long shared;
  volatile long directFallbacks;
  volatile long forces;
  volatile long waits;
  volatile long lastForceNanos;
  volatile long estimatedForceNanos;
  volatile int maximumCohort;

  void recordCohort(int count) {
    cohorts++;
    submitted += count;
    if (count > maximumCohort) maximumCohort = count;
  }

  void recordForce(long elapsedNanos) {
    long elapsed = Math.max(1, elapsedNanos);
    lastForceNanos = elapsed;
    long estimate = estimatedForceNanos;
    estimatedForceNanos = estimate == 0 ? elapsed : estimate + ((elapsed - estimate) >> 3);
  }
}
