package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

/** Atomic streaming properties artifact shared by run and recovery phases. */
final class TpccArtifact {
  record Recovery(String runId, String databaseDigest, long seed, int warehouses, boolean standard) {}

  private TpccArtifact() {}

  static String write(
      TpccConfig config,
      TpccMetrics metrics,
      TpccDatabaseIdentity identity,
      TpccProcessObservation before,
      TpccProcessObservation after,
      int rollbackProbes,
      int retryProbes) throws IOException {
    String runId = UUID.randomUUID().toString();
    Properties values = new Properties();
    values.setProperty("artifact.schema", "river-tpcc-acceptance-v2");
    values.setProperty("run.id", runId);
    values.setProperty("run.completed_at", Instant.now().toString());
    values.setProperty("run.label", "River engineering measurement; not official TPC-C; not tpmC");
    values.setProperty("run.scheduling_profile", config.scheduling() == TpccScheduling.STANDARD
        ? "standard_keying_and_think" : "nonstandard_no_wait_stress");
    values.setProperty("database.digest.sha256", identity.digest());
    configuration(values, config);
    environment(values);
    measurements(values, metrics, before, after, rollbackProbes, retryProbes);
    writeProperties(config.artifact(), values);
    return runId;
  }

  static Recovery read(TpccConfig config) throws IOException {
    Properties values = readProperties(config.artifact());
    require("river-tpcc-acceptance-v2".equals(values.getProperty("artifact.schema")),
        "unsupported artifact schema");
    require(Long.toString(config.seed()).equals(values.getProperty("config.seed")),
        "artifact seed differs from recovery configuration");
    require(Integer.toString(config.warehouses()).equals(values.getProperty("config.warehouses")),
        "artifact warehouse count differs from recovery configuration");
    require(Boolean.toString(config.standardScale()).equals(values.getProperty("config.standard_scale")),
        "artifact scale differs from recovery configuration");
    require(values.getProperty("recovery.run_id") == null,
        "artifact already contains recovery verification");
    return new Recovery(required(values, "run.id"), required(values, "database.digest.sha256"),
        Long.parseLong(required(values, "config.seed")),
        Integer.parseInt(required(values, "config.warehouses")),
        Boolean.parseBoolean(required(values, "config.standard_scale")));
  }

  static void markRecovered(TpccConfig config, Recovery recovery, String digest)
      throws IOException {
    Properties values = readProperties(config.artifact());
    require(recovery.runId().equals(values.getProperty("run.id")), "run identity changed");
    values.setProperty("recovery.verified_at", Instant.now().toString());
    values.setProperty("recovery.run_id", recovery.runId());
    values.setProperty("recovery.database_digest.sha256", digest);
    values.setProperty("recovery.external_restart_required", "true");
    writeProperties(config.artifact(), values);
  }

  private static void configuration(Properties values, TpccConfig config) {
    values.setProperty("config.url", config.url());
    values.setProperty("config.seed", Long.toString(config.seed()));
    values.setProperty("config.warehouses", Integer.toString(config.warehouses()));
    values.setProperty("config.standard_scale", Boolean.toString(config.standardScale()));
    values.setProperty("config.standard_one_warehouse", Boolean.toString(config.standardOneWarehouse()));
    values.setProperty("config.districts", Integer.toString(config.districts()));
    values.setProperty("config.customers_per_district", Integer.toString(config.customersPerDistrict()));
    values.setProperty("config.items", Integer.toString(config.itemCount()));
    values.setProperty("config.orders_per_district", Integer.toString(config.ordersPerDistrict()));
    values.setProperty("config.terminals", Integer.toString(config.terminals()));
    values.setProperty("config.terminal_homes", terminalHomes(config));
    values.setProperty("config.scheduling", config.scheduling().name());
    values.setProperty("config.mix", config.mix().name());
    values.setProperty("config.isolation_contract", config.isolation().name());
    values.setProperty("config.jdbc_isolation", config.isolation().jdbcLabel());
    values.setProperty("config.program_isolation", config.isolation().programLabel());
    values.setProperty("config.evidence", config.evidence().name());
    values.setProperty("config.warmup_seconds", Long.toString(config.warmup().toSeconds()));
    values.setProperty("config.measured_seconds", Long.toString(config.measured().toSeconds()));
    values.setProperty("config.batch_rows", Integer.toString(config.batchRows()));
    values.setProperty("config.maximum_attempts", Integer.toString(config.maximumAttempts()));
    values.setProperty("config.retry_base_nanos",
        Long.toString(TpccRetry.saturatedNanos(config.retryBase())));
    values.setProperty("config.retry_maximum_nanos",
        Long.toString(TpccRetry.saturatedNanos(config.retryMaximum())));
    values.setProperty("instrumentation.jfr", config.jfr() == null
        ? "disabled" : config.jfr().toAbsolutePath().toString());
    values.setProperty("config.nurand_load_last", Integer.toString(TpccNurandConstants.STANDARD.loadLast()));
    values.setProperty("config.nurand_run_last", Integer.toString(TpccNurandConstants.STANDARD.runLast()));
    values.setProperty("config.nurand_customer_id", Integer.toString(TpccNurandConstants.STANDARD.customerId()));
    values.setProperty("config.nurand_item_id", Integer.toString(TpccNurandConstants.STANDARD.itemId()));
    values.setProperty("bound.configured_load_batch_rows", Integer.toString(config.batchRows()));
    values.setProperty("bound.latency_buckets_per_family", "64");
    values.setProperty("bound.engine_high_water", "unavailable_via_jdbc");
  }

  private static String terminalHomes(TpccConfig config) {
    StringBuilder homes = new StringBuilder(config.terminals() * 5);
    for (int terminal = 0; terminal < config.terminals(); terminal++) {
      if (terminal > 0) homes.append(',');
      TpccTerminalHome home = TpccTerminalHome.at(config, terminal);
      homes.append(home.warehouse()).append(':').append(home.district());
    }
    return homes.toString();
  }

  private static void environment(Properties values) {
    Runtime runtime = Runtime.getRuntime();
    values.setProperty("environment.java_version", System.getProperty("java.version", "unknown"));
    values.setProperty("environment.vm", System.getProperty("java.vm.name", "unknown"));
    values.setProperty("environment.os", System.getProperty("os.name", "unknown") + " "
        + System.getProperty("os.version", "unknown") + " "
        + System.getProperty("os.arch", "unknown"));
    values.setProperty("environment.processors", Integer.toString(runtime.availableProcessors()));
    values.setProperty("environment.maximum_heap_bytes", Long.toString(runtime.maxMemory()));
  }

  private static void measurements(
      Properties values,
      TpccMetrics metrics,
      TpccProcessObservation before,
      TpccProcessObservation after,
      int rollbackProbes,
      int retryProbes) {
    values.setProperty("measurement.retries", Long.toString(metrics.retries()));
    values.setProperty("measurement.started_transactions", Long.toString(metrics.started()));
    values.setProperty("measurement.completed_transactions_at_cutoff",
        Long.toString(metrics.total()));
    values.setProperty("measurement.in_flight_at_cutoff",
        Long.toString(metrics.inFlightAtCutoff()));
    values.setProperty("measurement.transaction_attempts",
        Long.toString(metrics.transactionAttempts()));
    values.setProperty("measurement.drain_transaction_attempts",
        Long.toString(metrics.drainTransactionAttempts()));
    values.setProperty("measurement.attempt_id_first", Long.toString(metrics.firstAttemptId()));
    values.setProperty("measurement.attempt_id_last", Long.toString(metrics.lastAttemptId()));
    values.setProperty("measurement.unclassified_retry_failures",
        Long.toString(metrics.unclassifiedRetryFailures()));
    values.setProperty("measurement.drain_unclassified_retry_failures",
        Long.toString(metrics.drainUnclassifiedRetryFailures()));
    values.setProperty("measurement.retry_correlation_overflows",
        Long.toString(metrics.retryCorrelationOverflows()));
    values.setProperty("measurement.metrics_overflowed",
        Boolean.toString(metrics.overflowed()));
    values.setProperty("measurement.drain_completed_transactions",
        Long.toString(metrics.drainTotal()));
    values.setProperty("measurement.maximum_latency_us",
        Long.toString(metrics.maximumLatencyMicros()));
    values.setProperty(
        "measurement.protocol_requests", Long.toString(metrics.protocolRequests()));
    values.setProperty(
        "measurement.logical_exchanges", Long.toString(metrics.protocolRequests()));
    values.setProperty("measurement.physical_request_frames", "unavailable_via_jdbc");
    values.setProperty("measurement.physical_response_frames", "unavailable_via_jdbc");
    values.setProperty(
        "measurement.protocol_bytes_sent", Long.toString(metrics.protocolBytesSent()));
    values.setProperty(
        "measurement.protocol_bytes_received", Long.toString(metrics.protocolBytesReceived()));
    values.setProperty("measurement.drain_protocol_requests",
        Long.toString(metrics.drainProtocolRequests()));
    values.setProperty("measurement.drain_protocol_bytes_sent",
        Long.toString(metrics.drainProtocolBytesSent()));
    values.setProperty("measurement.drain_protocol_bytes_received",
        Long.toString(metrics.drainProtocolBytesReceived()));
    values.setProperty("measurement.rollback_probes", Integer.toString(rollbackProbes));
    values.setProperty("measurement.retry_probes", Integer.toString(retryProbes));
    for (StatusCode status : StatusCode.values()) {
      if (!status.isRetryable()) continue;
      String prefix = "measurement.retry_status."
          + status.name().toLowerCase(java.util.Locale.ROOT) + ".";
      values.setProperty(prefix + "server_outcomes",
          Long.toString(metrics.retryableOutcomes(status)));
      values.setProperty(prefix + "client_retries",
          Long.toString(metrics.clientRetries(status)));
      values.setProperty(prefix + "drain_server_outcomes",
          Long.toString(metrics.drainRetryableOutcomes(status)));
      values.setProperty(prefix + "drain_client_retries",
          Long.toString(metrics.drainClientRetries(status)));
    }
    for (TpccTransactionType type : TpccTransactionType.values()) {
      String prefix = "measurement." + type.name().toLowerCase(java.util.Locale.ROOT) + ".";
      values.setProperty(prefix + "committed", Long.toString(metrics.committed(type)));
      values.setProperty(
          prefix + "expected_rollbacks", Long.toString(metrics.expectedRollbacks(type)));
      values.setProperty(
          prefix + "retry_exhausted", Long.toString(metrics.retryExhausted(type)));
      values.setProperty(prefix + "failed", Long.toString(metrics.failed(type)));
      values.setProperty(prefix + "drain_committed", Long.toString(metrics.drainCommitted(type)));
      values.setProperty(prefix + "drain_expected_rollbacks",
          Long.toString(metrics.drainExpectedRollbacks(type)));
      values.setProperty(prefix + "drain_retry_exhausted",
          Long.toString(metrics.drainRetryExhausted(type)));
      values.setProperty(prefix + "drain_failed", Long.toString(metrics.drainFailed(type)));
      values.setProperty(
          prefix + "protocol_requests", Long.toString(metrics.protocolRequests(type)));
      values.setProperty(prefix + "drain_protocol_requests",
          Long.toString(metrics.drainProtocolRequests(type)));
      values.setProperty(prefix + "drain_protocol_bytes_sent",
          Long.toString(metrics.drainProtocolBytesSent(type)));
      values.setProperty(prefix + "drain_protocol_bytes_received",
          Long.toString(metrics.drainProtocolBytesReceived(type)));
      values.setProperty(prefix + "protocol_bytes_sent",
          Long.toString(metrics.protocolBytesSent(type)));
      values.setProperty(prefix + "protocol_bytes_received",
          Long.toString(metrics.protocolBytesReceived(type)));
      values.setProperty(prefix + "protocol_requests_per_attempt", String.format(
          java.util.Locale.ROOT, "%.1f", metrics.protocolRequestsPerAttempt(type)));
      values.setProperty(prefix + "transaction_attempts",
          Long.toString(metrics.transactionAttempts(type)));
      for (StatusCode status : StatusCode.values()) {
        if (!status.isRetryable()) continue;
        String statusPrefix = prefix + "retry_status."
            + status.name().toLowerCase(java.util.Locale.ROOT) + ".";
        values.setProperty(statusPrefix + "server_outcomes",
            Long.toString(metrics.retryableOutcomes(type, status)));
        values.setProperty(statusPrefix + "client_retries",
            Long.toString(metrics.clientRetries(type, status)));
        values.setProperty(statusPrefix + "drain_server_outcomes",
            Long.toString(metrics.drainRetryableOutcomes(type, status)));
        values.setProperty(statusPrefix + "drain_client_retries",
            Long.toString(metrics.drainClientRetries(type, status)));
      }
      values.setProperty(prefix + "p50_us_upper", Long.toString(metrics.percentileMicros(type, 50)));
      values.setProperty(prefix + "p95_us_upper", Long.toString(metrics.percentileMicros(type, 95)));
      values.setProperty(prefix + "p99_us_upper", Long.toString(metrics.percentileMicros(type, 99)));
      values.setProperty(prefix + "p99_9_us_upper",
          Long.toString(metrics.percentileMicrosPermille(type, 999)));
      values.setProperty(prefix + "maximum_us", Long.toString(metrics.maximumLatencyMicros(type)));
      for (int bucket = 0; bucket < 64; bucket++) {
        values.setProperty(prefix + "histogram." + bucket,
            Long.toString(metrics.histogram(type, bucket)));
      }
    }
    values.setProperty("process.heap_used_before", Long.toString(before.heapUsed()));
    values.setProperty("process.heap_used_after", Long.toString(after.heapUsed()));
    values.setProperty("process.heap_committed_after", Long.toString(after.heapCommitted()));
    values.setProperty("process.peak_pool_used_after", Long.toString(after.peakPoolUsed()));
    values.setProperty("process.gc_collections_delta", Long.toString(after.gcCollections() - before.gcCollections()));
    values.setProperty("process.gc_millis_delta", Long.toString(after.gcMillis() - before.gcMillis()));
    values.setProperty("process.worker_allocated_bytes", metrics.allocationObserved()
        ? Long.toString(metrics.allocatedBytes()) : "unavailable_on_this_vm");
  }

  private static Properties readProperties(Path path) throws IOException {
    Properties values = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      values.load(input);
    }
    return values;
  }

  private static void writeProperties(Path path, Properties values) throws IOException {
    Path absolute = path.toAbsolutePath();
    Path parent = absolute.getParent();
    if (parent != null) Files.createDirectories(parent);
    Path staged = absolute.resolveSibling(absolute.getFileName() + ".staged");
    try {
      try (OutputStream output = Files.newOutputStream(staged)) {
        values.store(output, "River TPC-C engineering acceptance");
      }
      Files.move(staged, absolute, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  private static String required(Properties values, String key) throws IOException {
    String value = values.getProperty(key);
    if (value == null || value.isEmpty()) throw new IOException("artifact missing " + key);
    return value;
  }

  private static void require(boolean condition, String message) throws IOException {
    if (!condition) throw new IOException(message);
  }
}
