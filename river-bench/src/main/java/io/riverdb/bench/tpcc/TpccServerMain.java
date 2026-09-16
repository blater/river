package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.app.RiverDaemonInstance;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;

/** Owns one temporary authenticated instance for the TPS shell tool. */
public final class TpccServerMain {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5450_4343_5345_5256L, 0x303030_303030_3031L);

  private TpccServerMain() {}

  public static void main(String[] arguments) throws Exception {
    ServerArguments configuration = ServerArguments.parse(arguments);
    RiverDaemonFileSystemResult filesystem = new RiverDaemonFileSystemResult();
    StatusCode status = RiverDaemonFileSystems.current(filesystem);
    if (!status.isOk()) {
      throw new IllegalStateException("TPS filesystem selection failed: " + status);
    }

    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    status = RiverDaemonInstance.open(
        configuration.directory(), filesystem.fileSystem(), new SecureRandom(), DATABASE,
        "localhost", InetAddress.getLoopbackAddress(), configuration.port(),
        LoopbackServerLimits.defaults(configuration.maximumConnections()),
        configuration.resourceRequest(), configuration.lockDiagnostics(),
        configuration.maximumConnections(), opened);
    if (!status.isOk()) {
      throw new IllegalStateException("TPS authenticated instance start failed: " + status);
    }

    RiverDaemonInstance instance = opened.instance();
    LoopbackRiverServer server = instance.server();
    RiverDatabase database = instance.database();
    TpccTraceRecording recording = null;
    TpccPerformanceCapture.ServerResult performanceCapture =
        new TpccPerformanceCapture.ServerResult(false, StatusCode.OK, "");
    try {
      System.out.println("server_client_config=" + instance.clientConfiguration());
      System.out.flush();
      Files.writeString(
          configuration.readyFile(),
          Integer.toString(server.port()),
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      System.out.println("server_ready=" + server.port());
      System.out.flush();
      if (configuration.traceStartFile() != null) {
        waitFor(configuration.traceStartFile());
      }
      if (configuration.jfr() != null) {
        recording = TpccTraceRecording.start(configuration.jfr(), "river-server-single-update");
        if (configuration.traceStartedFile() != null) {
          Files.writeString(
              configuration.traceStartedFile(), "started\n", StandardCharsets.US_ASCII,
              StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
      }
      performanceCapture = TpccPerformanceCapture.serve(
          database,
          configuration.metricsStartFile(),
          configuration.metricsStartedFile(),
          configuration.metricsStopFile(),
          configuration.metricsStoppedFile(),
          configuration.stopFile());
      if (configuration.stopFile() == null) {
        new CountDownLatch(1).await();
      } else {
        waitFor(configuration.stopFile());
      }
    } finally {
      shutdown(
          instance, recording, configuration.metricsFile(),
          configuration.maximumConnections(), performanceCapture);
    }
  }

  static void shutdown(
      RiverDaemonInstance instance,
      TpccTraceRecording recording,
      Path metricsFile,
      int admittedConnections,
      TpccPerformanceCapture.ServerResult performanceCapture) throws IOException {
    StatusCode serverStatus = StatusCode.RETRY;
    try {
      serverStatus = instance.server().close();
    } finally {
      try {
        if (recording != null) recording.close();
      } finally {
        try {
          if (serverStatus.isOk() || serverStatus == StatusCode.CLOSED) {
            writeMetrics(
                metricsFile, instance.database(), admittedConnections, performanceCapture);
          }
        } finally {
          close(instance, serverStatus);
        }
      }
    }
  }

  private static void writeMetrics(
      Path path,
      RiverDatabase database,
      int admittedConnections,
      TpccPerformanceCapture.ServerResult performanceCapture) {
    if (path == null) return;
    StringBuilder metrics = new StringBuilder(32 * 1024);
    metrics.append("server_metrics_scope=server_lifetime\n")
        .append("server_maximum_connections=").append(admittedConnections).append('\n')
        .append("server_maximum_active_transactions=").append(admittedConnections).append('\n')
        .append("server_active_transactions_at_capture=")
        .append(database.activeTransactionCount()).append('\n')
        .append("server_retained_snapshots_at_capture=")
        .append(database.retainedSnapshotCount()).append('\n')
        .append("server_active_locks_at_capture=")
        .append(database.activeLockCount()).append('\n')
        .append("server_waiting_locks_at_capture=")
        .append(database.waitingLockCount()).append('\n')
        .append("server_lock_waits_entered=").append(database.lockWaitsEntered()).append('\n')
        .append("server_lock_waits_actually_blocked=")
        .append(database.lockWaitsActuallyBlocked()).append('\n')
        .append("server_lock_wait_blocked_nanos=")
        .append(database.lockWaitBlockedNanos()).append('\n')
        .append("server_lock_waits_granted=").append(database.lockWaitsGranted()).append('\n')
        .append("server_lock_waits_timed_out=").append(database.lockWaitsTimedOut()).append('\n')
        .append("server_lock_waits_deadlocked=")
        .append(database.lockWaitsDeadlocked()).append('\n')
        .append("server_lock_waits_cancelled=").append(database.lockWaitsCancelled()).append('\n')
        .append("server_lock_escalation_supported=")
        .append(database.lockEscalationSupported()).append('\n')
        .append("server_lock_escalations=").append(database.lockEscalationCount()).append('\n');
    metrics.append("server_performance_capture_enabled=")
        .append(performanceCapture.enabled()).append('\n')
        .append("server_performance_capture_status=")
        .append(performanceCapture.status()).append('\n');
    if (performanceCapture.enabled()) {
      metrics.append("server_performance_capture_population=")
          .append("measured_attempt_window_including_drain\n");
    }
    metrics
        .append(performanceCapture.metrics());
    StatusCode diagnosticStatus = EmbeddedRiver.appendDeadlockDiagnostics(database, metrics);
    metrics.append("server_deadlock_diagnostics_status=")
        .append(diagnosticStatus).append('\n');
    StatusCode commitDiagnosticStatus = EmbeddedRiver.appendCommitDiagnostics(database, metrics);
    metrics.append("server_commit_diagnostics_status=")
        .append(commitDiagnosticStatus).append('\n');
    try {
      Files.writeString(
          path, metrics, StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    } catch (java.io.IOException failure) {
      System.err.println("TPS server metrics write failed: " + failure.getMessage());
    }
  }

  private static void waitFor(Path file) throws InterruptedException {
    while (!Files.exists(file)) Thread.sleep(10);
  }

  private static void close(RiverDaemonInstance instance, StatusCode serverStatus) {
    StatusCode status = instance.close();
    if (!serverStatus.isOk() && serverStatus != StatusCode.CLOSED) {
      System.err.println("TPS server shutdown failed: phase=server_stop status=" + serverStatus);
    } else if (!status.isOk() && status != StatusCode.CLOSED) {
      System.err.println("TPS server shutdown failed: phase=instance_close status=" + status);
    }
  }

  private record ServerArguments(
      Path directory, int port, int maximumConnections, Path readyFile,
      Path jfr, Path traceStartFile, Path traceStartedFile, Path stopFile,
      Path metricsFile, Path metricsStartFile, Path metricsStartedFile,
      Path metricsStopFile, Path metricsStoppedFile,
      long resourceMaximumBytes, long resourceDeliveryBytes,
      long resourceLockProviderBytes, long resourceVersionWorkspaceBytes,
      long resourcePageCacheBytes,
      long resourceStagingFrameBytes, long resourceStagedPageCapacity,
      long deadlockDiagnosticsBytes, int deadlockDiagnosticsEpochs,
      int deadlockDiagnosticsSignaturesPerEpoch,
      int deadlockDiagnosticsEventsPerEpoch,
      int deadlockDiagnosticsExemplarsPerSignature,
      int deadlockDiagnosticsMaximumCycleEdges) {
    // Development-tool profile used when a lifecycle wrapper does not override resources.
    // The embedded engine still requires and receives one explicit compiled request.
    private static final long DEFAULT_RESOURCE_MAXIMUM_BYTES = 1L << 30;
    private static final long DEFAULT_RESOURCE_DELIVERY_BYTES = 1L << 28;
    private static final long DEFAULT_RESOURCE_LOCK_PROVIDER_BYTES = 1L << 26;
    private static final long DEFAULT_RESOURCE_VERSION_WORKSPACE_BYTES = 1L << 26;
    private static final long DEFAULT_RESOURCE_PAGE_CACHE_BYTES = 1L << 28;
    private static final long DEFAULT_RESOURCE_STAGING_FRAME_BYTES = 1L << 26;
    private static final long DEFAULT_RESOURCE_STAGED_PAGE_CAPACITY = 4_096;

    private static ServerArguments parse(String[] arguments) {
      Builder builder = new Builder();
      for (String argument : arguments) builder.accept(argument);
      return builder.build();
    }

    private static final class Builder {
      private Path directory;
      private Path readyFile;
      private Path jfr;
      private Path traceStartFile;
      private Path traceStartedFile;
      private Path stopFile;
      private Path metricsFile;
      private Path metricsStartFile;
      private Path metricsStartedFile;
      private Path metricsStopFile;
      private Path metricsStoppedFile;
      private int port = -1;
      private int maximumConnections = -1;
      private long resourceMaximumBytes = DEFAULT_RESOURCE_MAXIMUM_BYTES;
      private long resourceDeliveryBytes = DEFAULT_RESOURCE_DELIVERY_BYTES;
      private long resourceLockProviderBytes = DEFAULT_RESOURCE_LOCK_PROVIDER_BYTES;
      private long resourceVersionWorkspaceBytes = DEFAULT_RESOURCE_VERSION_WORKSPACE_BYTES;
      private long resourcePageCacheBytes = DEFAULT_RESOURCE_PAGE_CACHE_BYTES;
      private long resourceStagingFrameBytes = DEFAULT_RESOURCE_STAGING_FRAME_BYTES;
      private long resourceStagedPageCapacity = DEFAULT_RESOURCE_STAGED_PAGE_CAPACITY;
      private long deadlockDiagnosticsBytes;
      private int deadlockDiagnosticsEpochs;
      private int deadlockDiagnosticsSignaturesPerEpoch;
      private int deadlockDiagnosticsEventsPerEpoch;
      private int deadlockDiagnosticsExemplarsPerSignature;
      private int deadlockDiagnosticsMaximumCycleEdges;

      private void accept(String argument) {
        int split = argument.indexOf('=');
        if (split < 0) throw unknown(argument);
        String key = argument.substring(0, split);
        String value = argument.substring(split + 1);
        switch (key) {
          case "--directory" -> directory = Path.of(value);
          case "--port" -> port = Integer.parseInt(value);
          case "--maximum-connections" -> maximumConnections = Integer.parseInt(value);
          case "--ready-file" -> readyFile = Path.of(value);
          case "--jfr" -> jfr = Path.of(value);
          case "--trace-start-file" -> traceStartFile = Path.of(value);
          case "--trace-started-file" -> traceStartedFile = Path.of(value);
          case "--stop-file" -> stopFile = Path.of(value);
          case "--metrics-file" -> metricsFile = Path.of(value);
          case "--metrics-start-file" -> metricsStartFile = Path.of(value);
          case "--metrics-started-file" -> metricsStartedFile = Path.of(value);
          case "--metrics-stop-file" -> metricsStopFile = Path.of(value);
          case "--metrics-stopped-file" -> metricsStoppedFile = Path.of(value);
          case "--resource-maximum-bytes" -> resourceMaximumBytes = Long.parseLong(value);
          case "--resource-delivery-bytes" -> resourceDeliveryBytes = Long.parseLong(value);
          case "--resource-lock-provider-bytes" -> resourceLockProviderBytes = Long.parseLong(value);
          case "--resource-version-workspace-bytes" -> resourceVersionWorkspaceBytes = Long.parseLong(value);
          case "--resource-page-cache-bytes" -> resourcePageCacheBytes = Long.parseLong(value);
          case "--resource-staging-frame-bytes" -> resourceStagingFrameBytes = Long.parseLong(value);
          case "--resource-staged-page-capacity" -> resourceStagedPageCapacity = Long.parseLong(value);
          case "--deadlock-diagnostics-bytes" -> deadlockDiagnosticsBytes = Long.parseLong(value);
          case "--deadlock-diagnostics-epochs" -> deadlockDiagnosticsEpochs = Integer.parseInt(value);
          case "--deadlock-diagnostics-signatures-per-epoch" ->
              deadlockDiagnosticsSignaturesPerEpoch = Integer.parseInt(value);
          case "--deadlock-diagnostics-events-per-epoch" ->
              deadlockDiagnosticsEventsPerEpoch = Integer.parseInt(value);
          case "--deadlock-diagnostics-exemplars-per-signature" ->
              deadlockDiagnosticsExemplarsPerSignature = Integer.parseInt(value);
          case "--deadlock-diagnostics-maximum-cycle-edges" ->
              deadlockDiagnosticsMaximumCycleEdges = Integer.parseInt(value);
          default -> throw unknown(argument);
        }
      }

      private ServerArguments build() {
        validate();
        return new ServerArguments(
            directory, port, maximumConnections, readyFile, jfr,
            traceStartFile, traceStartedFile, stopFile, metricsFile,
            metricsStartFile, metricsStartedFile, metricsStopFile, metricsStoppedFile,
            resourceMaximumBytes, resourceDeliveryBytes, resourceLockProviderBytes,
            resourceVersionWorkspaceBytes, resourcePageCacheBytes, resourceStagingFrameBytes,
            resourceStagedPageCapacity, deadlockDiagnosticsBytes, deadlockDiagnosticsEpochs,
            deadlockDiagnosticsSignaturesPerEpoch, deadlockDiagnosticsEventsPerEpoch,
            deadlockDiagnosticsExemplarsPerSignature, deadlockDiagnosticsMaximumCycleEdges);
      }

      private void validate() {
        validateBase();
        validateTraceStart();
        validateMetricsFiles();
      }

      private void validateBase() {
        if (directory == null || readyFile == null || port < 0 || port > 65_535
            || maximumConnections < 1 || resourceMaximumBytes <= 0
            || resourceDeliveryBytes <= 0 || resourceLockProviderBytes <= 0
            || resourceVersionWorkspaceBytes <= 0 || resourcePageCacheBytes <= 0
            || resourceStagingFrameBytes <= 0 || resourceStagedPageCapacity <= 0) {
          throw new IllegalArgumentException("invalid TPS server configuration");
        }
      }

      private void validateTraceStart() {
        if (traceStartFile != null && jfr == null) {
          throw new IllegalArgumentException("trace start file requires --jfr");
        }
        if (traceStartedFile != null && jfr == null) {
          throw new IllegalArgumentException("trace started file requires --jfr");
        }
      }

      private void validateMetricsFiles() {
        int metricsControlFiles = (metricsStartFile == null ? 0 : 1)
            + (metricsStartedFile == null ? 0 : 1)
            + (metricsStopFile == null ? 0 : 1)
            + (metricsStoppedFile == null ? 0 : 1);
        if (metricsControlFiles != 0 && metricsControlFiles != 4) {
          throw new IllegalArgumentException(
              "performance capture requires all four control files");
        }
      }

      private static IllegalArgumentException unknown(String argument) {
        return new IllegalArgumentException("unknown TPS server argument: " + argument);
      }
    }

    DatabaseResourcePlanRequest resourceRequest() {
      return new DatabaseResourcePlanRequest()
          .memory(resourceMaximumBytes, 0, 0, 0, resourceDeliveryBytes)
          .lockProviderBytes(resourceLockProviderBytes)
          .versionWorkspaceBytes(resourceVersionWorkspaceBytes)
          .indexedPageCache(resourcePageCacheBytes, resourceStagingFrameBytes)
          .capacity(
              maximumConnections, Integer.MAX_VALUE,
              resourceStagedPageCapacity, resourceDeliveryBytes)
          .maximumDelivery(
              Integer.MAX_VALUE, resourceStagedPageCapacity, resourceDeliveryBytes);
    }

    EmbeddedLockDiagnosticsConfig lockDiagnostics() {
      return deadlockDiagnosticsBytes == 0
          ? EmbeddedLockDiagnosticsConfig.disabled()
          : EmbeddedLockDiagnosticsConfig.bounded(
              deadlockDiagnosticsBytes, deadlockDiagnosticsEpochs,
              deadlockDiagnosticsSignaturesPerEpoch, deadlockDiagnosticsEventsPerEpoch,
              deadlockDiagnosticsExemplarsPerSignature,
              deadlockDiagnosticsMaximumCycleEdges);
    }
  }
}
