package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class TpccMetricsTest {
  @Test
  void combinesBoundedHistogramsAndOutcomes() {
    TpccMetrics first = new TpccMetrics();
    TpccMetrics second = new TpccMetrics();
    first.attemptStarted(TpccTransactionType.NEW_ORDER, 1, true);
    first.retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.CONFLICT, true, true,
        1, 1, 0, 1, 7);
    first.attemptStarted(TpccTransactionType.NEW_ORDER, 2, true);
    first.record(
        TpccTransactionType.NEW_ORDER, 1_000, new TpccRetry.Result(true, false, 1));
    second.attemptStarted(TpccTransactionType.NEW_ORDER, 3, true);
    second.retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.DEADLOCK, true, true,
        3, 1, 1, 1, 8);
    second.attemptStarted(TpccTransactionType.NEW_ORDER, 4, true);
    second.retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.DEADLOCK, true, true,
        4, 2, 1, 1, 9);
    second.attemptStarted(TpccTransactionType.NEW_ORDER, 5, true);
    second.retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.DEADLOCK, false, true,
        5, 2, 1, 2, 10);
    second.record(
        TpccTransactionType.NEW_ORDER, 4_000, new TpccRetry.Result(false, true, 2));
    first.protocol(TpccTransactionType.NEW_ORDER, 7, 800, 900);
    second.protocol(TpccTransactionType.NEW_ORDER, 11, 1_200, 1_300);
    first.add(second);
    assertEquals(1, first.committed(TpccTransactionType.NEW_ORDER));
    assertEquals(0, first.expectedRollbacks(TpccTransactionType.NEW_ORDER));
    assertEquals(1, first.retryExhausted(TpccTransactionType.NEW_ORDER));
    assertEquals(3, first.retries());
    assertEquals(1, first.retryableOutcomes(StatusCode.CONFLICT));
    assertEquals(3, first.retryableOutcomes(StatusCode.DEADLOCK));
    assertEquals(1, first.clientRetries(StatusCode.CONFLICT));
    assertEquals(2, first.clientRetries(StatusCode.DEADLOCK));
    assertEquals(18, first.protocolRequests());
    assertEquals(2_000, first.protocolBytesSent());
    assertEquals(2_200, first.protocolBytesReceived());
    assertEquals(18, first.protocolRequests(TpccTransactionType.NEW_ORDER));
    assertEquals(2_000, first.protocolBytesSent(TpccTransactionType.NEW_ORDER));
    assertEquals(2_200, first.protocolBytesReceived(TpccTransactionType.NEW_ORDER));
    assertEquals(3.6, first.protocolRequestsPerAttempt(TpccTransactionType.NEW_ORDER));
    assertEquals(5, first.transactionAttempts());
    assertEquals(4, first.percentileMicros(TpccTransactionType.NEW_ORDER, 99));
    assertEquals(4, first.maximumLatencyMicros(TpccTransactionType.NEW_ORDER));
    assertEquals(0, first.maximumLatencyMicros(TpccTransactionType.PAYMENT));
    assertFalse(first.overflowed());
  }

  @Test
  void overflowInvalidatesMetricsInsteadOfWrapping() throws Exception {
    TpccMetrics metrics = new TpccMetrics();
    java.lang.reflect.Field requests = TpccMetrics.class.getDeclaredField("protocolRequests");
    requests.setAccessible(true);
    requests.setLong(metrics, Long.MAX_VALUE);

    metrics.protocol(TpccTransactionType.PAYMENT, 1, 0, 0);

    assertEquals(Long.MAX_VALUE, metrics.protocolRequests());
    assertTrue(metrics.overflowed());
  }
}
