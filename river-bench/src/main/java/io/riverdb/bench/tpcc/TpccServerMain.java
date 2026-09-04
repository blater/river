package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerOpenResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;

/** Owns one temporary database and loopback listener for the TPS shell tool. */
public final class TpccServerMain {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5450_4343_5345_5256L, 0x303030_303030_3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  private TpccServerMain() {}

  public static void main(String[] arguments) throws Exception {
    ServerArguments configuration = ServerArguments.parse(arguments);
    Files.createDirectories(configuration.directory());

    DatabaseOpenResult opened = new DatabaseOpenResult();
    StatusCode status = EmbeddedRiver.create(
        configuration.resourceRequest(), configuration.directory(), DATABASE, GENERATION,
        configuration.maximumConnections(), configuration.lockDiagnostics(), opened);
    if (!status.isOk()) {
      throw new IllegalStateException("TPS database create failed: " + status);
    }

    LoopbackServerOpenResult listening = new LoopbackServerOpenResult();
    status = LoopbackRiverServer.start(
        opened.database(), configuration.port(), configuration.maximumConnections(), listening);
    if (!status.isOk()) {
      opened.database().close();
      throw new IllegalStateException("TPS loopback server start failed: " + status);
    }

    LoopbackRiverServer server = listening.server();
    RiverDatabase database = opened.database();
    TpccTraceRecording recording = null;
    TpccPerformanceCapture.ServerResult performanceCapture =
        new TpccPerformanceCapture.ServerResult(false, StatusCode.OK, "");
    try {
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
      if (recording != null) recording.close();
      writeMetrics(
          configuration.metricsFile(), database, configuration.maximumConnections(),
          performanceCapture);
      close(server, opened);
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

  private static void close(LoopbackRiverServer server, DatabaseOpenResult opened) {
    StatusCode serverStatus = server.close();
    StatusCode databaseStatus = opened.database().close();
    if (!serverStatus.isOk() || !databaseStatus.isOk()) {
      System.err.println(
          "TPS server shutdown failed: server=" + serverStatus
              + " database=" + databaseStatus);
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
      Path directory = null;
      Path readyFile = null;
      Path jfr = null;
      Path traceStartFile = null;
      Path traceStartedFile = null;
      Path stopFile = null;
      Path metricsFile = null;
      Path metricsStartFile = null;
      Path metricsStartedFile = null;
      Path metricsStopFile = null;
      Path metricsStoppedFile = null;
      int port = -1;
      int maximumConnections = -1;
      long resourceMaximumBytes = DEFAULT_RESOURCE_MAXIMUM_BYTES;
      long resourceDeliveryBytes = DEFAULT_RESOURCE_DELIVERY_BYTES;
      long resourceLockProviderBytes = DEFAULT_RESOURCE_LOCK_PROVIDER_BYTES;
      long resourceVersionWorkspaceBytes = DEFAULT_RESOURCE_VERSION_WORKSPACE_BYTES;
      long resourcePageCacheBytes = DEFAULT_RESOURCE_PAGE_CACHE_BYTES;
      long resourceStagingFrameBytes = DEFAULT_RESOURCE_STAGING_FRAME_BYTES;
      long resourceStagedPageCapacity = DEFAULT_RESOURCE_STAGED_PAGE_CAPACITY;
      long deadlockDiagnosticsBytes = 0;
      int deadlockDiagnosticsEpochs = 0;
      int deadlockDiagnosticsSignaturesPerEpoch = 0;
      int deadlockDiagnosticsEventsPerEpoch = 0;
      int deadlockDiagnosticsExemplarsPerSignature = 0;
      int deadlockDiagnosticsMaximumCycleEdges = 0;
      for (String argument : arguments) {
        if (argument.startsWith("--directory=")) {
          directory = Path.of(argument.substring("--directory=".length()));
        } else if (argument.startsWith("--port=")) {
          port = Integer.parseInt(argument.substring("--port=".length()));
        } else if (argument.startsWith("--maximum-connections=")) {
          maximumConnections = Integer.parseInt(
              argument.substring("--maximum-connections=".length()));
        } else if (argument.startsWith("--ready-file=")) {
          readyFile = Path.of(argument.substring("--ready-file=".length()));
        } else if (argument.startsWith("--jfr=")) {
          jfr = Path.of(argument.substring("--jfr=".length()));
        } else if (argument.startsWith("--trace-start-file=")) {
          traceStartFile = Path.of(argument.substring("--trace-start-file=".length()));
        } else if (argument.startsWith("--trace-started-file=")) {
          traceStartedFile = Path.of(argument.substring("--trace-started-file=".length()));
        } else if (argument.startsWith("--stop-file=")) {
          stopFile = Path.of(argument.substring("--stop-file=".length()));
        } else if (argument.startsWith("--metrics-file=")) {
          metricsFile = Path.of(argument.substring("--metrics-file=".length()));
        } else if (argument.startsWith("--metrics-start-file=")) {
          metricsStartFile = Path.of(
              argument.substring("--metrics-start-file=".length()));
        } else if (argument.startsWith("--metrics-started-file=")) {
          metricsStartedFile = Path.of(
              argument.substring("--metrics-started-file=".length()));
        } else if (argument.startsWith("--metrics-stop-file=")) {
          metricsStopFile = Path.of(
              argument.substring("--metrics-stop-file=".length()));
        } else if (argument.startsWith("--metrics-stopped-file=")) {
          metricsStoppedFile = Path.of(
              argument.substring("--metrics-stopped-file=".length()));
        } else if (argument.startsWith("--resource-maximum-bytes=")) {
          resourceMaximumBytes = Long.parseLong(
              argument.substring("--resource-maximum-bytes=".length()));
        } else if (argument.startsWith("--resource-delivery-bytes=")) {
          resourceDeliveryBytes = Long.parseLong(
              argument.substring("--resource-delivery-bytes=".length()));
        } else if (argument.startsWith("--resource-lock-provider-bytes=")) {
          resourceLockProviderBytes = Long.parseLong(
              argument.substring("--resource-lock-provider-bytes=".length()));
        } else if (argument.startsWith("--resource-version-workspace-bytes=")) {
          resourceVersionWorkspaceBytes = Long.parseLong(
              argument.substring("--resource-version-workspace-bytes=".length()));
        } else if (argument.startsWith("--resource-page-cache-bytes=")) {
          resourcePageCacheBytes = Long.parseLong(
              argument.substring("--resource-page-cache-bytes=".length()));
        } else if (argument.startsWith("--resource-staging-frame-bytes=")) {
          resourceStagingFrameBytes = Long.parseLong(
              argument.substring("--resource-staging-frame-bytes=".length()));
        } else if (argument.startsWith("--resource-staged-page-capacity=")) {
          resourceStagedPageCapacity = Long.parseLong(
              argument.substring("--resource-staged-page-capacity=".length()));
        } else if (argument.startsWith("--deadlock-diagnostics-bytes=")) {
          deadlockDiagnosticsBytes = Long.parseLong(
              argument.substring("--deadlock-diagnostics-bytes=".length()));
        } else if (argument.startsWith("--deadlock-diagnostics-epochs=")) {
          deadlockDiagnosticsEpochs = Integer.parseInt(
              argument.substring("--deadlock-diagnostics-epochs=".length()));
        } else if (argument.startsWith("--deadlock-diagnostics-signatures-per-epoch=")) {
          deadlockDiagnosticsSignaturesPerEpoch = Integer.parseInt(argument.substring(
              "--deadlock-diagnostics-signatures-per-epoch=".length()));
        } else if (argument.startsWith("--deadlock-diagnostics-events-per-epoch=")) {
          deadlockDiagnosticsEventsPerEpoch = Integer.parseInt(
              argument.substring("--deadlock-diagnostics-events-per-epoch=".length()));
        } else if (argument.startsWith("--deadlock-diagnostics-exemplars-per-signature=")) {
          deadlockDiagnosticsExemplarsPerSignature = Integer.parseInt(argument.substring(
              "--deadlock-diagnostics-exemplars-per-signature=".length()));
        } else if (argument.startsWith("--deadlock-diagnostics-maximum-cycle-edges=")) {
          deadlockDiagnosticsMaximumCycleEdges = Integer.parseInt(argument.substring(
              "--deadlock-diagnostics-maximum-cycle-edges=".length()));
        } else {
          throw new IllegalArgumentException("unknown TPS server argument: " + argument);
        }
      }
      if (directory == null || readyFile == null || port < 0 || port > 65_535
          || maximumConnections < 1 || resourceMaximumBytes <= 0
          || resourceDeliveryBytes <= 0 || resourceLockProviderBytes <= 0
          || resourceVersionWorkspaceBytes <= 0
          || resourcePageCacheBytes <= 0 || resourceStagingFrameBytes <= 0
          || resourceStagedPageCapacity <= 0) {
        throw new IllegalArgumentException("invalid TPS server configuration");
      }
      if (traceStartFile != null && jfr == null) {
        throw new IllegalArgumentException("trace start file requires --jfr");
      }
      if (traceStartedFile != null && jfr == null) {
        throw new IllegalArgumentException("trace started file requires --jfr");
      }
      int metricsControlFiles = (metricsStartFile == null ? 0 : 1)
          + (metricsStartedFile == null ? 0 : 1)
          + (metricsStopFile == null ? 0 : 1)
          + (metricsStoppedFile == null ? 0 : 1);
      if (metricsControlFiles != 0 && metricsControlFiles != 4) {
        throw new IllegalArgumentException(
            "performance capture requires all four control files");
      }
      return new ServerArguments(
          directory, port, maximumConnections, readyFile, jfr,
          traceStartFile, traceStartedFile, stopFile, metricsFile,
          metricsStartFile, metricsStartedFile, metricsStopFile, metricsStoppedFile,
          resourceMaximumBytes, resourceDeliveryBytes, resourceLockProviderBytes,
          resourceVersionWorkspaceBytes,
          resourcePageCacheBytes, resourceStagingFrameBytes,
          resourceStagedPageCapacity,
          deadlockDiagnosticsBytes, deadlockDiagnosticsEpochs,
          deadlockDiagnosticsSignaturesPerEpoch, deadlockDiagnosticsEventsPerEpoch,
          deadlockDiagnosticsExemplarsPerSignature,
          deadlockDiagnosticsMaximumCycleEdges);
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
