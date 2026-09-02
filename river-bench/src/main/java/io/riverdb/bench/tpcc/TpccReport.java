package io.riverdb.bench.tpcc;

/** Bounded human-readable companion to the persisted artifact. */
final class TpccReport {
  private TpccReport() {}

  static void configuration(TpccConfig config) {
    System.out.println("profile=" + (config.standardScale()
        ? "standard-" + config.warehouses() + "-warehouse" : "tiny-nonstandard"));
    System.out.println("warehouses=" + config.warehouses()
        + " terminals=" + config.terminals()
        + " warmup_seconds=" + config.warmup().toSeconds()
        + " measured_seconds=" + config.measured().toSeconds()
        + " batch_rows=" + config.batchRows()
        + " maximum_attempts=" + config.maximumAttempts()
        + " scheduling=" + config.scheduling() + " phase=" + config.phase()
        + " evidence=" + config.evidence()
        + " jfr=" + (config.jfr() == null ? "disabled" : config.jfr().toAbsolutePath()));
    System.out.println("bounded_metrics=5 transaction types x 64 latency buckets per terminal");
  }

  static void results(TpccConfig config, TpccMetrics metrics) {
    double seconds = config.measured().toNanos() / 1_000_000_000.0;
    System.out.println("result_label=River engineering measurement; not official TPC-C; not tpmC");
    System.out.println("scheduling_profile=" + (config.scheduling() == TpccScheduling.STANDARD
        ? "standard_keying_and_think" : "nonstandard_no_wait_stress"));
    System.out.println("engineering_committed_transactions_per_second="
        + String.format(java.util.Locale.ROOT, "%.3f", metrics.totalCommitted() / seconds));
    System.out.println("whole_transaction_retries=" + metrics.retries());
    System.out.println("started_transactions=" + metrics.started());
    System.out.println("completed_transactions=" + metrics.total());
    System.out.println("transaction_attempts=" + metrics.transactionAttempts());
    System.out.println("in_flight_at_cutoff=" + metrics.inFlightAtCutoff());
    System.out.println("completed_transactions_at_cutoff=" + metrics.total());
    System.out.println("maximum_latency_us=" + metrics.maximumLatencyMicros());
    System.out.println("protocol_requests=" + metrics.protocolRequests());
    System.out.println("logical_exchanges=" + metrics.protocolRequests()
        + " boundary=jdbc_statement");
    System.out.println("physical_request_frames=unavailable_via_jdbc");
    System.out.println("physical_response_frames=unavailable_via_jdbc");
    System.out.println("protocol_bytes_sent=" + metrics.protocolBytesSent());
    System.out.println("protocol_bytes_received=" + metrics.protocolBytesReceived());
    System.out.println("protocol_requests_per_commit=" + String.format(
        java.util.Locale.ROOT, "%.1f",
        metrics.totalCommitted() == 0
            ? 0.0 : metrics.protocolRequests() / (double) metrics.totalCommitted()));
    System.out.println("protocol_requests_per_transaction_attempt=" + String.format(
        java.util.Locale.ROOT, "%.1f",
        metrics.transactionAttempts() == 0
            ? 0.0 : metrics.protocolRequests() / (double) metrics.transactionAttempts()));
    System.out.println("worker_allocated_bytes=" + (metrics.allocationObserved()
        ? metrics.allocatedBytes() : "unavailable_on_this_vm"));
    for (TpccTransactionType type : TpccTransactionType.values()) {
      System.out.println("transaction=" + type + " committed=" + metrics.committed(type)
          + " expected_rollbacks=" + metrics.expectedRollbacks(type)
          + " retries=" + metrics.retries(type)
          + " retry_exhausted=" + metrics.retryExhausted(type)
          + " failed=" + metrics.failed(type)
          + " protocol_requests=" + metrics.protocolRequests(type)
          + " protocol_requests_per_attempt=" + String.format(
              java.util.Locale.ROOT, "%.1f", metrics.protocolRequestsPerAttempt(type))
          + " p50_us_upper_bound=" + metrics.percentileMicros(type, 50)
          + " p95_us_upper_bound=" + metrics.percentileMicros(type, 95)
          + " p99_us_upper_bound=" + metrics.percentileMicros(type, 99)
          + " p99_9_us_upper_bound=" + metrics.percentileMicrosPermille(type, 999)
          + " maximum_us=" + metrics.maximumLatencyMicros(type));
    }
    for (int kind = 0; kind < TpccRiverNewOrder.FAILURE_KINDS; kind++) {
      long failures = metrics.newOrderProgramFailures(kind);
      if (failures > 0) {
        System.out.println("new_order_program_failure="
            + TpccRiverNewOrder.failureName(kind) + " count=" + failures);
      }
    }
  }

  static String secondsSince(long start) {
    return String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - start) / 1_000_000_000.0);
  }
}
