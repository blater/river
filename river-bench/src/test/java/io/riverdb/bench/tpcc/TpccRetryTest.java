package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class TpccRetryTest {
  @Test
  void distinguishesExpectedBusinessRollbackFromRetryExhaustion() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny"
    });
    TpccRetry.Result result = TpccRetry.execute(
        () -> false, config, System.nanoTime() + 5_000_000_000L,
        TpccRetryObserver.NONE);
    assertFalse(result.committed());
    assertFalse(result.retryExhausted());
    assertEquals(0, result.retries());
  }

  @Test
  void reportsSerializationExhaustionAsBoundedRollback() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny", "--maximum-attempts=2"
    });
    int[] attempts = {0};
    TpccRetry.Result result = TpccRetry.execute(() -> {
      attempts[0]++;
      throw new SQLException(
          "injected conflict", "40001",
          io.riverdb.base.error.StatusCode.CONFLICT.stableCode());
    }, config, System.nanoTime() + 5_000_000_000L, TpccRetryObserver.NONE);
    assertFalse(result.committed());
    assertEquals(true, result.retryExhausted());
    assertEquals(1, result.retries());
    assertEquals(2, attempts[0]);
  }

  @Test
  void doesNotMergeUnknownSerializationSqlStateIntoRiverRetry() throws Exception {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny", "--maximum-attempts=2"
    });
    SQLException failure = org.junit.jupiter.api.Assertions.assertThrows(
        SQLException.class,
        () -> TpccRetry.execute(
            () -> { throw new SQLException("unknown serialization", "40001"); },
            config,
            System.nanoTime() + 5_000_000_000L,
            TpccRetryObserver.NONE));
    assertEquals(0, failure.getErrorCode());
  }

  @Test
  void exponentialDelayUsesConfiguredMaximumInsteadOfArbitraryExponent() {
    assertEquals(1L << 30, TpccRetry.exponentialDelayCap(1, 1L << 40, 31));
    assertEquals(1L << 40, TpccRetry.exponentialDelayCap(1, 1L << 40, 41));
    assertEquals(1L << 40, TpccRetry.exponentialDelayCap(1, 1L << 40, Integer.MAX_VALUE));
  }

  @Test
  void exponentialDelayAndJitterHandleLongAddressabilityBoundary() {
    assertEquals(Long.MAX_VALUE,
        TpccRetry.exponentialDelayCap(3, Long.MAX_VALUE, Integer.MAX_VALUE));
    assertEquals(Long.MAX_VALUE,
        TpccRetry.saturatedNanos(Duration.ofSeconds(Long.MAX_VALUE)));
    for (int sample = 0; sample < 100; sample++) {
      long delay = TpccRetry.randomDelay(Long.MAX_VALUE);
      assertTrue(delay >= 0);
    }
  }
}
