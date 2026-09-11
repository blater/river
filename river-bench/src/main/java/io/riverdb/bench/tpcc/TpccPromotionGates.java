package io.riverdb.bench.tpcc;

import java.sql.SQLException;

/** Post-run semantic gates; no incomplete workload can be promoted. */
final class TpccPromotionGates {
  private TpccPromotionGates() {}

  static void verify(
      TpccMetrics metrics, int rollbackProbes, int retryProbes, TpccConfig config)
      throws SQLException {
    if (metrics.overflowed()) {
      throw new SQLException("correctness gate: measurement counter overflow");
    }
    if (metrics.retry().unclassifiedRetryFailures() != 0
        || metrics.retry().drainUnclassifiedRetryFailures() != 0) {
      throw new SQLException("correctness gate: unclassified retry outcome");
    }
    if (metrics.retry().retryCorrelationOverflows() != 0
        || metrics.retry().retryCorrelationCount()
            != metrics.retry().retryableOutcomes() + metrics.retry().drainRetryableOutcomes()) {
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
      TpccAlpha3PromotionPolicy.verifyMix(metrics, config, total);
    }
    if (rollbackProbes < 1) throw new SQLException("promotion gate: missing expected rollback");
    if (retryProbes < 1) throw new SQLException("promotion gate: missing expected retry");
    if (config.evidence() == TpccEvidenceMode.ALPHA3) {
      TpccAlpha3PromotionPolicy.verifySample(metrics, config);
    }
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

}
