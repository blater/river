package io.riverdb.bench.tpcc;

import java.sql.SQLException;

/** Post-run semantic gates; no incomplete workload can be promoted. */
final class TpccPromotionGates {
  private static final double[] EXPECTED = {0.45, 0.43, 0.04, 0.04, 0.04};

  private TpccPromotionGates() {}

  static void verify(
      TpccMetrics metrics, int rollbackProbes, int retryProbes, TpccConfig config)
      throws SQLException {
    if (metrics.overflowed()) {
      throw new SQLException("correctness gate: measurement counter overflow");
    }
    if (metrics.unclassifiedRetryFailures() != 0
        || metrics.drainUnclassifiedRetryFailures() != 0) {
      throw new SQLException("correctness gate: unclassified retry outcome");
    }
    if (metrics.retryCorrelationOverflows() != 0
        || metrics.retryCorrelationCount()
            != metrics.retryableOutcomes() + metrics.drainRetryableOutcomes()) {
      throw new SQLException("correctness gate: incomplete retry correlation");
    }
    if (metrics.overflowed()) {
      throw new SQLException("correctness gate: measurement counter overflow");
    }
    verifyTerminalOutcomes(metrics);
    long total = 0;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      long family = metrics.total(type);
      if (config.evidence() == TpccEvidenceMode.ALPHA3
          && config.mix().includes(type) && family == 0) {
        throw new SQLException("measurement gate: missing " + type + " family");
      }
      if (!config.mix().includes(type) && family != 0) {
        throw new SQLException("measurement gate: unexpected " + type + " family");
      }
      if (family > Long.MAX_VALUE - total) {
        throw new SQLException("correctness gate: measurement counter overflow");
      }
      total += family;
    }
    if (config.evidence() == TpccEvidenceMode.ALPHA3) {
      verifyMix(metrics, config, total);
    }
    if (rollbackProbes < 1) throw new SQLException("promotion gate: missing expected rollback");
    if (retryProbes < 1) throw new SQLException("promotion gate: missing expected retry");
    if (config.evidence() == TpccEvidenceMode.ALPHA3) verifyAlpha3Sample(metrics, config);
  }

  static void verifyTerminalOutcomes(TpccMetrics metrics) throws SQLException {
    if (metrics.overflowed()) {
      throw new SQLException("correctness gate: measurement counter overflow");
    }
    for (TpccTransactionType type : TpccTransactionType.values()) {
      if (metrics.retryExhausted(type) != 0 || metrics.drainRetryExhausted(type) != 0) {
        throw new SQLException("correctness gate: retry-exhausted " + type + " transactions");
      }
      if (metrics.failed(type) != 0 || metrics.drainFailed(type) != 0) {
        throw new SQLException("correctness gate: failed " + type + " transactions");
      }
    }
  }

  private static void verifyAlpha3Sample(TpccMetrics metrics, TpccConfig config)
      throws SQLException {
    if (!config.standardScale() || config.scheduling() != TpccScheduling.NO_WAIT_STRESS
        || config.mix() != TpccWorkloadMix.STANDARD || !config.isolation().common()) {
      throw new SQLException(
          "alpha3 sample: requires standard scale/mix, common isolation, and no-wait scheduling");
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

  private static void verifyMix(TpccMetrics metrics, TpccConfig config, long total)
      throws SQLException {
    if (config.mix() == TpccWorkloadMix.NEW_ORDER
        || config.mix() == TpccWorkloadMix.PAYMENT) return;
    for (TpccTransactionType type : TpccTransactionType.values()) {
      double expected = config.mix() == TpccWorkloadMix.STANDARD
          ? EXPECTED[type.ordinal()]
          : type == TpccTransactionType.NEW_ORDER || type == TpccTransactionType.PAYMENT
              ? 0.5 : 0.0;
      double actual = metrics.total(type) / (double) total;
      double statistical = 5.0 * Math.sqrt(expected * (1.0 - expected) / total);
      double tolerance = Math.max(0.03, statistical);
      if (Math.abs(actual - expected) > tolerance) {
        throw new SQLException("measurement gate: material mix deviation for " + type
            + " expected=" + expected + " actual=" + actual + " tolerance=" + tolerance);
      }
    }
  }
}
