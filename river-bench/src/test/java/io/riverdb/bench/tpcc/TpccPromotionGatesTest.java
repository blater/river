package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

final class TpccPromotionGatesTest {
  @Test
  void acceptsCleanTerminalOutcomes() {
    assertDoesNotThrow(() -> TpccPromotionGates.verifyTerminalOutcomes(new TpccMetrics()));
  }

  @Test
  void rejectsRetryExhaustionAfterTheMeasurementCutoff() {
    TpccMetrics metrics = new TpccMetrics();
    metrics.recordDrain(
        TpccTransactionType.STOCK_LEVEL, new TpccRetry.Result(false, true, 31));

    SQLException failure = assertThrows(
        SQLException.class, () -> TpccPromotionGates.verifyTerminalOutcomes(metrics));
    assertTrue(failure.getMessage().contains("retry-exhausted STOCK_LEVEL"));
  }

  @Test
  void rejectsFailureAfterTheMeasurementCutoff() {
    TpccMetrics metrics = new TpccMetrics();
    metrics.failure(TpccTransactionType.DELIVERY, 1, false);

    SQLException failure = assertThrows(
        SQLException.class, () -> TpccPromotionGates.verifyTerminalOutcomes(metrics));
    assertTrue(failure.getMessage().contains("failed DELIVERY"));
  }

  @Test
  void rejectsCounterOverflow() throws Exception {
    TpccMetrics metrics = new TpccMetrics();
    java.lang.reflect.Field requests = TpccMetrics.class.getDeclaredField("protocolRequests");
    requests.setAccessible(true);
    requests.setLong(metrics, Long.MAX_VALUE);
    metrics.protocol(TpccTransactionType.NEW_ORDER, 1, 0, 0);

    SQLException failure = assertThrows(
        SQLException.class, () -> TpccPromotionGates.verifyTerminalOutcomes(metrics));
    assertTrue(failure.getMessage().contains("counter overflow"));
  }
}
