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
    first.retry().attemptStarted(TpccTransactionType.NEW_ORDER, 1, true);
    first.retry().retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.CONFLICT, true, true,
        1, 1, 0, 1, 7);
    first.retry().attemptStarted(TpccTransactionType.NEW_ORDER, 2, true);
    first.record(
        TpccTransactionType.NEW_ORDER, 1_000, new TpccRetry.Result(true, false, 1));
    second.retry().attemptStarted(TpccTransactionType.NEW_ORDER, 3, true);
    second.retry().retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.DEADLOCK, true, true,
        3, 1, 1, 1, 8);
    second.retry().attemptStarted(TpccTransactionType.NEW_ORDER, 4, true);
    second.retry().retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.DEADLOCK, true, true,
        4, 2, 1, 1, 9);
    second.retry().attemptStarted(TpccTransactionType.NEW_ORDER, 5, true);
    second.retry().retryableOutcome(
        TpccTransactionType.NEW_ORDER, StatusCode.DEADLOCK, false, true,
        5, 2, 1, 2, 10);
    second.record(
        TpccTransactionType.NEW_ORDER, 4_000, new TpccRetry.Result(false, true, 2));
    first.protocol().record(TpccTransactionType.NEW_ORDER, 7, 800, 900);
    second.protocol().record(TpccTransactionType.NEW_ORDER, 11, 1_200, 1_300);
    first.add(second);
    assertEquals(1, first.committed(TpccTransactionType.NEW_ORDER));
    assertEquals(0, first.expectedRollbacks(TpccTransactionType.NEW_ORDER));
    assertEquals(1, first.retryExhausted(TpccTransactionType.NEW_ORDER));
    assertEquals(3, first.retry().retries());
    assertEquals(1, first.retry().retryableOutcomes(StatusCode.CONFLICT));
    assertEquals(3, first.retry().retryableOutcomes(StatusCode.DEADLOCK));
    assertEquals(1, first.retry().clientRetries(StatusCode.CONFLICT));
    assertEquals(2, first.retry().clientRetries(StatusCode.DEADLOCK));
    assertEquals(18, first.protocol().requests());
    assertEquals(2_000, first.protocol().bytesSent());
    assertEquals(2_200, first.protocol().bytesReceived());
    assertEquals(18, first.protocol().requests(TpccTransactionType.NEW_ORDER));
    assertEquals(2_000, first.protocol().bytesSent(TpccTransactionType.NEW_ORDER));
    assertEquals(2_200, first.protocol().bytesReceived(TpccTransactionType.NEW_ORDER));
    assertEquals(3.6, first.protocol().requestsPerAttempt(
        TpccTransactionType.NEW_ORDER, first.retry().transactionAttempts(
            TpccTransactionType.NEW_ORDER)));
    assertEquals(5, first.retry().transactionAttempts());
    assertEquals(4, first.latency().percentileMicros(TpccTransactionType.NEW_ORDER, 99));
    assertEquals(4, first.latency().maximumLatencyMicros(TpccTransactionType.NEW_ORDER));
    assertEquals(0, first.latency().maximumLatencyMicros(TpccTransactionType.PAYMENT));
    assertFalse(first.overflowed());
  }

  @Test
  void overflowInvalidatesMetricsInsteadOfWrapping() throws Exception {
    TpccMetrics metrics = new TpccMetrics();
    java.lang.reflect.Field requests = TpccProtocolMetrics.class.getDeclaredField("requests");
    requests.setAccessible(true);
    requests.setLong(metrics.protocol(), Long.MAX_VALUE);

    metrics.protocol().record(TpccTransactionType.PAYMENT, 1, 0, 0);

    assertEquals(Long.MAX_VALUE, metrics.protocol().requests());
    assertTrue(metrics.overflowed());
  }

  @Test
  void latencyQueryOverflowRemainsVisibleAndSurvivesMerge() throws Exception {
    TpccMetrics source = new TpccMetrics();
    java.lang.reflect.Field latencyField = TpccMetrics.class.getDeclaredField("latency");
    latencyField.setAccessible(true);
    TpccLatencyMetrics latency = (TpccLatencyMetrics) latencyField.get(source);
    java.lang.reflect.Field histogramField = TpccLatencyMetrics.class.getDeclaredField("histogram");
    histogramField.setAccessible(true);
    long[][] histogram = (long[][]) histogramField.get(latency);
    histogram[TpccTransactionType.PAYMENT.ordinal()][0] = Long.MAX_VALUE;
    histogram[TpccTransactionType.PAYMENT.ordinal()][1] = Long.MAX_VALUE;

    source.latency().percentileMicros(TpccTransactionType.PAYMENT, 99);
    assertTrue(source.overflowed());

    TpccMetrics combined = new TpccMetrics();
    combined.add(source);
    assertTrue(combined.overflowed());
  }

}
