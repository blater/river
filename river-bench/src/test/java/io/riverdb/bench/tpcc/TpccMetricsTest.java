package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class TpccMetricsTest {
  @Test
  void combinesBoundedHistogramsAndOutcomes() {
    TpccMetrics first = new TpccMetrics();
    TpccMetrics second = new TpccMetrics();
    first.record(
        TpccTransactionType.NEW_ORDER, 1_000, new TpccRetry.Result(true, false, 1));
    second.record(
        TpccTransactionType.NEW_ORDER, 4_000, new TpccRetry.Result(false, true, 2));
    first.protocol(TpccTransactionType.NEW_ORDER, 7, 800, 900);
    second.protocol(TpccTransactionType.NEW_ORDER, 11, 1_200, 1_300);
    first.add(second);
    assertEquals(1, first.committed(TpccTransactionType.NEW_ORDER));
    assertEquals(0, first.expectedRollbacks(TpccTransactionType.NEW_ORDER));
    assertEquals(1, first.retryExhausted(TpccTransactionType.NEW_ORDER));
    assertEquals(3, first.retries());
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
  }
}
