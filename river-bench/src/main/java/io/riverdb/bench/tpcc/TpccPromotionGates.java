package io.riverdb.bench.tpcc;

import java.sql.SQLException;

/** Post-run semantic gates; no incomplete workload can be promoted. */
final class TpccPromotionGates {
  private static final double[] EXPECTED = {0.45, 0.43, 0.04, 0.04, 0.04};

  private TpccPromotionGates() {}

  static void verify(
      TpccMetrics metrics, int rollbackProbes, int retryProbes, TpccConfig config)
      throws SQLException {
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      long family = metrics.total(type);
      if (family == 0) throw new SQLException("promotion gate: missing " + type + " family");
      if (metrics.failed(type) != 0) {
        throw new SQLException("promotion gate: failed " + type + " transactions");
      }
      total += family;
    }
    for (TpccTransactionType type : TpccTransactionType.values()) {
      double expected = EXPECTED[type.ordinal()];
      double actual = metrics.total(type) / (double) total;
      double statistical = 5.0 * Math.sqrt(expected * (1.0 - expected) / total);
      double tolerance = Math.max(0.03, statistical);
      if (Math.abs(actual - expected) > tolerance) {
        throw new SQLException("promotion gate: material mix deviation for " + type
            + " expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
      }
    }
    if (rollbackProbes < 1) throw new SQLException("promotion gate: missing expected rollback");
    if (retryProbes < 1) throw new SQLException("promotion gate: missing expected retry");
    if (config.evidence() == TpccEvidenceMode.ALPHA3) verifyAlpha3Sample(metrics, config);
  }

  private static void verifyAlpha3Sample(TpccMetrics metrics, TpccConfig config)
      throws SQLException {
    if (!config.standardScale() || config.scheduling() != TpccScheduling.NO_WAIT_STRESS) {
      throw new SQLException("alpha3 sample: requires standard scale and no-wait scheduling");
    }
    if (metrics.total() < 100_000) {
      throw new SQLException("alpha3 sample: fewer than 100000 completed transactions: "
          + metrics.totalCommitted());
    }
    for (TpccTransactionType type : TpccTransactionType.values()) {
      if (metrics.retryExhausted(type) != 0 || metrics.failed(type) != 0) {
        throw new SQLException("alpha3 sample: unexpected failed transactions in " + type);
      }
    }
    System.out.println("alpha3_sample=passed completed=" + metrics.total());
    System.out.println("alpha3_promotion=requires_10_samples_and_95ci");
  }
}
