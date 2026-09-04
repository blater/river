package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;

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
        + " scheduling=" + config.scheduling()
        + " mix=" + config.mix()
        + " isolation_contract=" + config.isolation()
        + " jdbc_isolation=" + config.isolation().jdbcLabel()
        + " program_isolation=" + config.isolation().programLabel()
        + " phase=" + config.phase()
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
    System.out.println("drain_completed_transactions=" + metrics.drainTotal());
    System.out.println("transaction_attempts=" + metrics.transactionAttempts());
    System.out.println("drain_transaction_attempts=" + metrics.drainTransactionAttempts());
    System.out.println("attempt_id_first=" + metrics.firstAttemptId());
    System.out.println("attempt_id_last=" + metrics.lastAttemptId());
    System.out.println("unclassified_retry_failures=" + metrics.unclassifiedRetryFailures());
    System.out.println("drain_unclassified_retry_failures="
        + metrics.drainUnclassifiedRetryFailures());
    System.out.println("retry_correlation_overflows=" + metrics.retryCorrelationOverflows());
    System.out.println("metrics_overflowed=" + metrics.overflowed());
    System.out.println("server_retryable_outcomes=" + metrics.retryableOutcomes());
    System.out.println("drain_server_retryable_outcomes=" + metrics.drainRetryableOutcomes());
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
    System.out.println("drain_protocol_requests=" + metrics.drainProtocolRequests());
    System.out.println("drain_protocol_bytes_sent=" + metrics.drainProtocolBytesSent());
    System.out.println("drain_protocol_bytes_received=" + metrics.drainProtocolBytesReceived());
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
    for (StatusCode status : StatusCode.values()) {
      long outcomes = metrics.retryableOutcomes(status);
      long clientRetries = metrics.clientRetries(status);
      long drainOutcomes = metrics.drainRetryableOutcomes(status);
      long drainRetries = metrics.drainClientRetries(status);
      if (outcomes != 0 || clientRetries != 0 || drainOutcomes != 0 || drainRetries != 0) {
        System.out.println("retry_status=" + status
            + " server_outcomes=" + outcomes
            + " client_retries=" + clientRetries
            + " drain_server_outcomes=" + drainOutcomes
            + " drain_client_retries=" + drainRetries);
      }
    }
    for (TpccTransactionType type : TpccTransactionType.values()) {
      System.out.println("transaction=" + type + " committed=" + metrics.committed(type)
          + " expected_rollbacks=" + metrics.expectedRollbacks(type)
          + " retries=" + metrics.retries(type)
          + " retry_exhausted=" + metrics.retryExhausted(type)
          + " failed=" + metrics.failed(type)
          + " drain_committed=" + metrics.drainCommitted(type)
          + " drain_expected_rollbacks=" + metrics.drainExpectedRollbacks(type)
          + " drain_retry_exhausted=" + metrics.drainRetryExhausted(type)
          + " drain_failed=" + metrics.drainFailed(type)
          + " protocol_requests=" + metrics.protocolRequests(type)
          + " drain_protocol_requests=" + metrics.drainProtocolRequests(type)
          + " protocol_requests_per_attempt=" + String.format(
              java.util.Locale.ROOT, "%.1f", metrics.protocolRequestsPerAttempt(type))
          + " p50_us_upper_bound=" + metrics.percentileMicros(type, 50)
          + " p95_us_upper_bound=" + metrics.percentileMicros(type, 95)
          + " p99_us_upper_bound=" + metrics.percentileMicros(type, 99)
          + " p99_9_us_upper_bound=" + metrics.percentileMicrosPermille(type, 999)
          + " maximum_us=" + metrics.maximumLatencyMicros(type));
      for (StatusCode status : StatusCode.values()) {
        long outcomes = metrics.retryableOutcomes(type, status);
        long clientRetries = metrics.clientRetries(type, status);
        long drainOutcomes = metrics.drainRetryableOutcomes(type, status);
        long drainRetries = metrics.drainClientRetries(type, status);
        if (outcomes != 0 || clientRetries != 0 || drainOutcomes != 0 || drainRetries != 0) {
          System.out.println("transaction_retry_status=" + type
              + " status=" + status
              + " server_outcomes=" + outcomes
              + " client_retries=" + clientRetries
              + " drain_server_outcomes=" + drainOutcomes
              + " drain_client_retries=" + drainRetries);
        }
      }
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
