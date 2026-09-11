package io.riverdb.bench.tpcc;

import java.sql.SQLException;

/** Alpha3's workload mix and sample policy. */
final class TpccAlpha3PromotionPolicy {
  private static final double[] EXPECTED = {0.45, 0.43, 0.04, 0.04, 0.04};

  private TpccAlpha3PromotionPolicy() {}

  static void verifySample(TpccMetrics metrics, TpccConfig config) throws SQLException {
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

  static void verifyMix(TpccMetrics metrics, TpccConfig config, long total)
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
