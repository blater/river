package io.riverdb.engine.sql;

import io.riverdb.sql.SqlJoinChain;

/** Allocation-free EXPLAIN ANALYZE counters for universal JOIN execution. */
final class SqlUniversalJoinMetrics {
  private final long[] candidates = new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] onTrue = new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private final long[] nullExtensions = new long[SqlJoinChain.MAXIMUM_JOIN_STAGES];
  private long roots;
  private long whereTrue;

  void root() { roots++; }
  void candidate(int stage) { candidates[stage]++; }
  void onTrue(int stage) { onTrue[stage]++; }
  void nullExtension(int stage) { nullExtensions[stage]++; }
  void whereTrue() { whereTrue++; }
  long roots() { return roots; }
  long candidates(int stage) { return candidates[stage]; }
  long onTrueCount(int stage) { return onTrue[stage]; }
  long nullExtensions(int stage) { return nullExtensions[stage]; }
  long whereTrueCount() { return whereTrue; }

  void reset() {
    roots = 0;
    whereTrue = 0;
    for (int stage = 0; stage < candidates.length; stage++) {
      candidates[stage] = 0;
      onTrue[stage] = 0;
      nullExtensions[stage] = 0;
    }
  }
}
