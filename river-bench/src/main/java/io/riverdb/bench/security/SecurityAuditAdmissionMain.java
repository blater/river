package io.riverdb.bench.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.IsolationLevel;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.PreparedOpenResult;
import io.riverdb.engine.api.ProgramOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionProgramArguments;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.client.RiverClientConnection;
import io.riverdb.client.RiverClientOpenResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.apfs.ApfsRiverDaemonFileSystem;
import io.riverdb.platform.riverd.linux.LinuxRiverDaemonFileSystem;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import io.riverdb.server.CredentialValidityFence;
import io.riverdb.server.CredentialValidityFenceOpenResult;
import io.riverdb.server.SecurityAuditLog;
import io.riverdb.server.SecurityAuditLogFactory;
import io.riverdb.server.SecurityAuditOpenResult;
import io.riverdb.server.SecurityAuditSnapshot;
import java.net.InetAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.SSLContext;
import org.HdrHistogram.Histogram;

/**
 * Small authenticated loopback driver for matched security-audit admission runs.
 * The workload is deliberately fixed and engine-neutral; this class does not
 * own benchmark comparison or promotion policy.
 */
public final class SecurityAuditAdmissionMain {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PLAIN_READ = "SELECT value FROM audit_probe WHERE id=1";
  private static final String DENIED_WRITE = "INSERT INTO audit_probe VALUES (1000000, 7)";
  private static final String PREPARED_READ = "SELECT value FROM audit_probe WHERE id=?";
  private static final String PROGRAM_PROBE = "SELECT id FROM audit_probe WHERE id=?";
  private static final long DATABASE_HIGH = 0x41554449544c4f47L;
  private static final long DATABASE_LOW = 0x41444d495353494fL;
  private static final int MAX_DECISIONS_PER_REQUEST = 2;

  private SecurityAuditAdmissionMain() { }

  public static void main(String[] args) {
    int status = run(args);
    if (status != 0) System.exit(status);
  }

  static int run(String[] args) {
    Arguments configuration;
    try {
      configuration = Arguments.parse(args);
      if (configuration == null) return 2;
      return execute(configuration);
    } catch (RunFailure failure) {
      System.err.println("security-audit admission: " + failure.getMessage());
      return 2;
    } catch (Exception failure) {
      System.err.println("security-audit admission: " + failure);
      return 2;
    }
  }

  private static int execute(Arguments configuration) throws Exception {
    Files.createDirectories(configuration.outputDirectory);
    Path artifact = configuration.outputDirectory.resolve("artifact.json");
    Path digest = configuration.outputDirectory.resolve("artifact.sha256");
    if (Files.exists(artifact) || Files.exists(digest)) {
      throw new RunFailure("output already contains an artifact");
    }

    Path databaseDirectory = configuration.outputDirectory.resolve("database");
    Path auditDirectory = configuration.outputDirectory.resolve("audit");
    Files.createDirectories(databaseDirectory);
    Files.createDirectories(auditDirectory);
    Files.setPosixFilePermissions(auditDirectory, java.util.Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE));

    RiverDatabase database = null;
    LoopbackRiverServer server = null;
    CredentialValidityFence validityFence = null;
    AuditTlsMaterial tls = null;
    TokenAuthenticator authenticator = null;
    byte[] token = "river-security-audit-admission-token".getBytes(StandardCharsets.UTF_8);
    Worker[] workers = new Worker[configuration.clients];
    SecurityAuditSnapshot before = null;
    SecurityAuditSnapshot after = null;
    long expectedDecisions = 0;
    long issued = 0;
    long completed = 0;
    boolean deniedWriteEffectVerified = false;
    JvmMeasurement jvmBefore = null;
    JvmMeasurement jvmAfter = null;
    try {
      DatabaseOpenResult databaseResult = new DatabaseOpenResult();
      require(
          EmbeddedRiver.create(
              databaseRequest(configuration.clients),
              databaseDirectory,
              DatabaseIncarnation.of(DATABASE_HIGH, DATABASE_LOW),
              WalGeneration.of(1),
              configuration.clients + 2,
              databaseResult),
          "create embedded database");
      database = databaseResult.database();
      seedSchema(database);

      tls = AuditTlsMaterial.create(configuration.outputDirectory);
      TokenAuthenticatorOpenResult authenticatorResult = new TokenAuthenticatorOpenResult();
      require(
          TokenAuthenticator.create(
              token,
              token.length,
              1,
              SessionPermissions.READ,
              authenticatorResult),
          "create read-only authenticator");
      authenticator = authenticatorResult.authenticator();

      LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
      SecurityAuditLog audit = openAudit(auditDirectory, true);
      validityFence = openValidityFence();
      require(
          LoopbackRiverServer.startAuthenticated(
              database,
              InetAddress.getByName("127.0.0.1"),
              0,
              tls.serverContext,
              authenticator,
              audit,
              validityFence,
              limits(configuration),
              serverResult),
          "start authenticated loopback server");
      server = serverResult.server();

      for (int client = 0; client < workers.length; client++) {
        workers[client] = new Worker(client, configuration, server, tls.clientContext, token);
        workers[client].open();
      }
      if (configuration.mode == Mode.PERFORMANCE) {
        TimedStart timed = runTimed(configuration, workers);
        before = timed.audit;
        jvmBefore = timed.jvm;
        after = timed.auditAfter;
        jvmAfter = timed.jvmAfter;
      } else {
        before = server.auditSnapshot();
        runFixed(configuration, workers);
        after = server.auditSnapshot();
      }
      for (Worker worker : workers) {
        issued += worker.issued;
        completed += worker.completed;
        expectedDecisions += worker.expectedDecisions;
      }
      if (after.decisions() - before.decisions() != expectedDecisions) {
        throw new RunFailure(
            "audit decision mismatch: expected " + expectedDecisions
                + " observed " + (after.decisions() - before.decisions()));
      }
      if (issued != completed) {
        throw new RunFailure("issued/completed mismatch: " + issued + "/" + completed);
      }
      if (configuration.mode == Mode.CORRECTNESS
          && issued != (long) configuration.clients * configuration.requestsPerClient) {
        throw new RunFailure("fixed-count request mismatch: " + issued);
      }
      for (Worker worker : workers) require(worker.close(), "close client " + worker.clientId);
      deniedWriteEffectVerified = verifyDeniedWriteDidNotMutate(database);

      long recordsBeforeRestart = server.auditRecordCount();
      require(server.close(), "close first authenticated server");
      server = null;
      LoopbackServerOpenResult restartResult = new LoopbackServerOpenResult();
      SecurityAuditLog reopenedAudit = openAudit(auditDirectory, false);
      validityFence = openValidityFence();
      require(
          LoopbackRiverServer.startAuthenticated(
              database,
              InetAddress.getByName("127.0.0.1"),
              0,
              tls.serverContext,
              authenticator,
              reopenedAudit,
              validityFence,
              limits(configuration),
              restartResult),
          "restart authenticated loopback server");
      server = restartResult.server();
      if (server.auditRecordCount() != recordsBeforeRestart) {
        throw new RunFailure("audit restart record count mismatch");
      }
      require(server.close(), "close restarted authenticated server");
      server = null;
      require(database.close(), "close database");
      database = null;
      try {
        tls.close();
      } catch (IOException failure) {
        throw new RunFailure("TLS cleanup failed: " + failure);
      }
      tls = null;
      Arrays.fill(token, (byte) 0);

      writeArtifact(
          configuration,
          before,
          after,
          workers,
          issued,
          completed,
          expectedDecisions,
          recordsBeforeRestart,
          deniedWriteEffectVerified,
          jvmBefore,
          jvmAfter);
      return 0;
    } finally {
      RunFailure cleanupFailure = null;
      for (Worker worker : workers) {
        if (worker != null) {
          StatusCode status = worker.close();
          if (!status.isOk() && status != StatusCode.CLOSED && cleanupFailure == null) {
            cleanupFailure = new RunFailure("client cleanup failed: " + status);
          }
        }
      }
      if (server != null) {
        StatusCode status = server.close();
        if (!status.isOk() && status != StatusCode.CLOSED && cleanupFailure == null) {
          cleanupFailure = new RunFailure("server cleanup failed: " + status);
        }
      }
      if (database != null) {
        StatusCode status = database.close();
        if (!status.isOk() && status != StatusCode.CLOSED && cleanupFailure == null) {
          cleanupFailure = new RunFailure("database cleanup failed: " + status);
        }
      }
      if (tls != null) {
        try {
          tls.close();
        } catch (IOException failure) {
          if (cleanupFailure == null) cleanupFailure = new RunFailure("TLS cleanup failed: " + failure);
        }
      }
      if (validityFence != null) validityFence.close();
      Arrays.fill(token, (byte) 0);
      if (cleanupFailure != null) throw cleanupFailure;
    }
  }

  private static CredentialValidityFence openValidityFence() throws RunFailure {
    CredentialValidityFenceOpenResult result = new CredentialValidityFenceOpenResult();
    long now = System.currentTimeMillis();
    require(
        CredentialValidityFence.create(now - 1_000L, now + 86_400_000L, result),
        "create credential validity fence");
    return result.fence();
  }

  private static void runFixed(Arguments configuration, Worker[] workers) throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    for (Worker worker : workers) worker.startLatch = start;
    for (Worker worker : workers) worker.thread = Thread.ofPlatform().start(worker::runFixed);
    start.countDown();
    for (Worker worker : workers) worker.thread.join();
    checkWorkers(workers);
  }

  private static TimedStart runTimed(
      Arguments configuration, Worker[] workers) throws InterruptedException {
    CountDownLatch warmupStart = new CountDownLatch(1);
    CountDownLatch warmupDone = new CountDownLatch(workers.length);
    CountDownLatch measuredStart = new CountDownLatch(1);
    CountDownLatch measuredDone = new CountDownLatch(workers.length);
    CountDownLatch measurementRelease = new CountDownLatch(1);
    long warmupDeadline = System.nanoTime() + configuration.warmupSeconds * 1_000_000_000L;
    for (Worker worker : workers) {
      worker.warmupStart = warmupStart;
      worker.warmupDone = warmupDone;
      worker.measuredStart = measuredStart;
      worker.measuredDone = measuredDone;
      worker.measurementRelease = measurementRelease;
      worker.warmupDeadline = warmupDeadline;
      worker.thread = Thread.ofPlatform().start(worker::runTimed);
    }
    SecurityAuditSnapshot before = null;
    JvmMeasurement jvmBefore = null;
    SecurityAuditSnapshot after = null;
    JvmMeasurement jvmAfter = null;
    InterruptedException interrupted = null;
    try {
      warmupStart.countDown();
      warmupDone.await();
      for (Worker worker : workers) worker.resetMeasuredCounters();
      before = workers[0].server.auditSnapshot();
      jvmBefore = JvmMeasurement.capture(workers);
      long measuredDeadline = System.nanoTime()
          + configuration.measuredSeconds * 1_000_000_000L;
      for (Worker worker : workers) worker.measuredDeadline = measuredDeadline;
      measuredStart.countDown();
      measuredDone.await();
      after = workers[0].server.auditSnapshot();
      jvmAfter = JvmMeasurement.capture(workers);
    } finally {
      warmupStart.countDown();
      measuredStart.countDown();
      measurementRelease.countDown();
      for (Worker worker : workers) {
        while (worker.thread.isAlive()) {
          try {
            worker.thread.join();
          } catch (InterruptedException failure) {
            interrupted = failure;
          }
        }
      }
      if (interrupted != null) throw interrupted;
    }
    checkWorkers(workers);
    return new TimedStart(before, jvmBefore, after, jvmAfter);
  }

  private static void checkWorkers(Worker[] workers) {
    for (Worker worker : workers) {
      if (worker.failure != null) {
        throw new RunFailure("client " + worker.clientId + " failed: " + worker.failure);
      }
    }
  }

  private static void seedSchema(RiverDatabase database) {
    SessionOpenResult sessionResult = new SessionOpenResult();
    require(database.createSession(sessionResult), "open seed session");
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    StatusCode status = session.execute(
        "CREATE TABLE audit_probe (id BIGINT PRIMARY KEY, value BIGINT)", result);
    if (status.isOk()) status = session.execute("INSERT INTO audit_probe VALUES (1, 1)", result);
    StatusCode close = session.close();
    if (!status.isOk()) throw new RunFailure("seed schema: " + status);
    require(close, "close seed session");
  }

  private static boolean verifyDeniedWriteDidNotMutate(RiverDatabase database) {
    SessionOpenResult sessionResult = new SessionOpenResult();
    require(database.createSession(sessionResult), "open effect verification session");
    RiverSession session = sessionResult.session();
    QueryOpenResult queryResult = new QueryOpenResult();
    RowResult row = new RowResult();
    CommandResult closeResult = new CommandResult();
    StatusCode status = session.beginQuery(
        "SELECT id FROM audit_probe WHERE id=1000000", queryResult);
    boolean available = false;
    if (status.isOk()) {
      RiverQuery query = queryResult.query();
      status = query.next(row);
      available = status.isOk() && row.isAvailable();
      StatusCode close = query.close(closeResult);
      if (status.isOk()) status = close;
    }
    StatusCode sessionClose = session.close();
    if (status.isOk()) status = sessionClose;
    require(status, "verify denied write effect");
    if (available) throw new RunFailure("denied write created a row");
    return true;
  }

  private static DatabaseResourcePlanRequest databaseRequest(int clients) {
    return new DatabaseResourcePlanRequest()
        .memory(256_000_000L, 0, 0, 0, 64_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(clients + 2, Integer.MAX_VALUE, 800, 64_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 800, 64_000_000L);
  }

  private static LoopbackServerLimits limits(Arguments configuration) {
    return new LoopbackServerLimits(configuration.clients, 5_000, 30_000);
  }

  private static SecurityAuditLog openAudit(Path path, boolean create) {
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    require(provider().openDirectory(path, directoryResult),
        "open verified audit directory");
    RiverDirectory directory = directoryResult.directory();
    SecurityAuditOpenResult auditResult = new SecurityAuditOpenResult();
    StatusCode status = create
        ? SecurityAuditLogFactory.create(
            directory, DatabaseIncarnation.of(DATABASE_HIGH, DATABASE_LOW), 1,
            SecurityAuditLogFactory.DEFAULT_ACTIVE_MAXIMUM_BYTES,
            SecurityAuditLogFactory.DEFAULT_PENDING_MAXIMUM_BYTES, auditResult)
        : SecurityAuditLogFactory.open(
            directory, DatabaseIncarnation.of(DATABASE_HIGH, DATABASE_LOW), 1,
            SecurityAuditLogFactory.DEFAULT_ACTIVE_MAXIMUM_BYTES,
            SecurityAuditLogFactory.DEFAULT_PENDING_MAXIMUM_BYTES, auditResult);
    require(status, create ? "create security audit" : "open security audit");
    return auditResult.audit();
  }

  private static io.riverdb.platform.riverd.RiverDaemonFileSystem provider() {
    return "Mac OS X".equals(System.getProperty("os.name"))
        ? new ApfsRiverDaemonFileSystem() : new LinuxRiverDaemonFileSystem();
  }

  private static void writeArtifact(
      Arguments configuration,
      SecurityAuditSnapshot before,
      SecurityAuditSnapshot after,
      Worker[] workers,
      long issued,
      long completed,
      long expectedDecisions,
      long records,
      boolean deniedWriteEffectVerified,
      JvmMeasurement jvmBefore,
      JvmMeasurement jvmAfter) throws IOException, NoSuchAlgorithmException {
    ObjectNode root = JSON.createObjectNode();
    root.put("schema_version", 1);
    root.put("artifact_type", "security_audit_admission");
    root.put("mode", configuration.mode.name().toLowerCase());
    root.put("clients", configuration.clients);
    root.put("requests_per_client", configuration.requestsPerClient);
    root.put("seed", configuration.seed);
    root.put("warmup_seconds", configuration.warmupSeconds);
    root.put("measured_seconds", configuration.measuredSeconds);
    root.put("issued", issued);
    root.put("completed", completed);
    root.put("expected_decisions", expectedDecisions);
    root.put("audit_records_after_restart", records);
    root.put("denied_write_effect_verified", deniedWriteEffectVerified);
    root.put("request_correlation",
        "workload synthetic request ids; audit telemetry aggregate only (record correlations not independently inspected)");
    root.put("legacy_authenticator_cleanup", "unavailable; caller_token_zeroed");
    root.put("java_runtime", System.getProperty("java.runtime.version"));
    root.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    ObjectNode operations = root.putObject("operations");
    long[] operationCounts = new long[Operation.values().length];
    long[] statusCounts = new long[StatusCode.values().length];
    long latencyCount = 0;
    long latencyTotal = 0;
    long latencyMaximum = 0;
    for (Worker worker : workers) {
      for (int index = 0; index < operationCounts.length; index++) {
        operationCounts[index] += worker.operationCounts[index];
      }
      for (int index = 0; index < statusCounts.length; index++) {
        statusCounts[index] += worker.statusCounts[index];
      }
      latencyCount += worker.latencyCount;
      latencyTotal += worker.latencyTotal;
      latencyMaximum = Math.max(latencyMaximum, worker.latencyMaximum);
    }
    for (Operation operation : Operation.values()) operations.put(
        operation.name().toLowerCase(), operationCounts[operation.ordinal()]);
    ObjectNode statuses = root.putObject("statuses");
    for (StatusCode status : StatusCode.values()) {
      if (statusCounts[status.ordinal()] != 0) {
        statuses.put(status.name().toLowerCase(), statusCounts[status.ordinal()]);
      }
    }
    ObjectNode latency = root.putObject("latency_ns");
    latency.put("count", latencyCount);
    latency.put("total", latencyTotal);
    latency.put("maximum", latencyMaximum);
    Histogram combinedLatency = new Histogram(120_000_000_000L, 3);
    for (Worker worker : workers) combinedLatency.add(worker.latency);
    latency.put("p50", combinedLatency.getValueAtPercentile(50.0));
    latency.put("p99", combinedLatency.getValueAtPercentile(99.0));
    latency.put("p999", combinedLatency.getValueAtPercentile(99.9));
    ObjectNode jvm = root.putObject("jvm");
    if (jvmBefore == null || jvmAfter == null) {
      jvm.put("scope", "whole_jvm_process");
      jvm.put("available", false);
      jvm.put("unavailable_reason", "correctness mode has no timed JVM window");
      jvm.put("monitor_blocked_available", false);
      jvm.put("monitor_blocked_unavailable_reason",
          "virtual_server_threads_not_exposed_by_threadmxbean");
    } else {
      jvm.put("scope", "whole_jvm_process");
      jvm.put("available", jvmBefore.available && jvmAfter.available);
      long processCpu = deltaOrUnavailable(jvmBefore.cpuNanos, jvmAfter.cpuNanos);
      jvm.put("cpu_nanos", processCpu);
      jvm.put("process_cpu_nanos", processCpu);
      jvm.put("monitor_blocked_nanos", -1);
      jvm.put("monitor_blocked_available", false);
      jvm.put("monitor_thread_set_stable", false);
      jvm.put("monitor_blocked_unavailable_reason",
          "virtual_server_threads_not_exposed_by_threadmxbean");
      jvm.put("allocated_bytes",
          deltaOrUnavailable(jvmBefore.allocatedBytes, jvmAfter.allocatedBytes));
      jvm.put("gc_collections", deltaOrUnavailable(jvmBefore.gcCollections, jvmAfter.gcCollections));
      jvm.put("gc_millis", deltaOrUnavailable(jvmBefore.gcMillis, jvmAfter.gcMillis));
    }
    putTelemetry(root.putObject("audit_before"), before);
    putTelemetry(root.putObject("audit_after"), after);
    putTelemetryDelta(root.putObject("audit_delta"), before, after);
    if (configuration.mode == Mode.CORRECTNESS) {
      ArrayNode requests = root.putArray("requests");
      for (Worker worker : workers) {
        for (Observation observation : worker.observations) {
          ObjectNode request = requests.addObject();
          request.put("request_id", observation.requestId);
          request.put("client", observation.clientId);
          request.put("ordinal", observation.ordinal);
          request.put("operation", Operation.values()[observation.operation].name().toLowerCase());
          request.put("expected_status", expectedStatus(Operation.values()[observation.operation]).name());
          request.put("status", StatusCode.values()[observation.status].name());
          request.put("admitted", observation.admitted);
          request.put("latency_ns", observation.latencyNanos);
        }
      }
    }
    byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    Path partial = configuration.outputDirectory.resolve("artifact.json.partial");
    Files.write(partial, bytes, StandardOpenOption.CREATE_NEW);
    try {
      Files.move(partial, configuration.outputDirectory.resolve("artifact.json"),
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException failure) {
      Files.deleteIfExists(partial);
      throw new IOException("atomic artifact publication unavailable", failure);
    }
    String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    Files.writeString(
        configuration.outputDirectory.resolve("artifact.sha256"),
        checksum + "  artifact.json\n",
        StandardCharsets.UTF_8);
  }

  private static void putTelemetryDelta(ObjectNode target,
      SecurityAuditSnapshot before, SecurityAuditSnapshot after) {
    long[] beforeHistogram = before.cohortHistogram();
    long[] afterHistogram = after.cohortHistogram();
    target.put("decisions", after.decisions() - before.decisions());
    target.put("appended_bytes", after.appendedBytes() - before.appendedBytes());
    target.put("batches", after.batches() - before.batches());
    target.put("force_calls", after.forceCalls() - before.forceCalls());
    target.put("force_nanos", after.forceNanos() - before.forceNanos());
    target.put("pending_bytes_high_water", after.pendingBytesHighWater());
    target.put("capacity_rejections", after.capacityRejections() - before.capacityRejections());
    target.put("pressure_rejections", after.pressureRejections() - before.pressureRejections());
    target.put("cancellations", after.cancellations() - before.cancellations());
    target.put("fences", after.fences() - before.fences());
    target.put("durable_frontier", after.durableFrontier() - before.durableFrontier());
    ArrayNode histogram = target.putArray("cohort_histogram");
    for (int index = 0; index < beforeHistogram.length; index++) {
      histogram.add(afterHistogram[index] - beforeHistogram[index]);
    }
  }

  private static void putTelemetry(ObjectNode target, SecurityAuditSnapshot snapshot) {
    target.put("decisions", snapshot.decisions());
    target.put("appended_bytes", snapshot.appendedBytes());
    target.put("batches", snapshot.batches());
    target.put("force_calls", snapshot.forceCalls());
    target.put("force_nanos", snapshot.forceNanos());
    target.put("pending_bytes_high_water", snapshot.pendingBytesHighWater());
    target.put("capacity_rejections", snapshot.capacityRejections());
    target.put("pressure_rejections", snapshot.pressureRejections());
    target.put("cancellations", snapshot.cancellations());
    target.put("fences", snapshot.fences());
    target.put("durable_frontier", snapshot.durableFrontier());
    ArrayNode histogram = target.putArray("cohort_histogram");
    for (long value : snapshot.cohortHistogram()) histogram.add(value);
  }

  private static long deltaOrUnavailable(long before, long after) {
    return before < 0 || after < before ? -1 : after - before;
  }

  private static boolean sameThreadSet(JvmMeasurement before, JvmMeasurement after) {
    return Arrays.equals(before.threadIds, after.threadIds);
  }

  private static StatusCode expectedStatus(Operation operation) {
    return operation == Operation.DENIED_WRITE ? StatusCode.ACCESS_DENIED : StatusCode.OK;
  }

  private static void require(StatusCode status, String action) {
    if (status == null || !status.isOk()) throw new RunFailure(action + ": " + status);
  }

  private record TimedStart(
      SecurityAuditSnapshot audit,
      JvmMeasurement jvm,
      SecurityAuditSnapshot auditAfter,
      JvmMeasurement jvmAfter) { }

  private static final class JvmMeasurement {
    private final boolean available;
    private final long cpuNanos;
    private final long monitorBlockedNanos;
    private final long allocatedBytes;
    private final long gcCollections;
    private final long gcMillis;
    private final long[] threadIds;

    private JvmMeasurement(boolean available, long cpuNanos, long monitorBlockedNanos,
        long allocatedBytes, long gcCollections, long gcMillis, long[] threadIds) {
      this.available = available;
      this.cpuNanos = cpuNanos;
      this.monitorBlockedNanos = monitorBlockedNanos;
      this.allocatedBytes = allocatedBytes;
      this.gcCollections = gcCollections;
      this.gcMillis = gcMillis;
      this.threadIds = threadIds;
    }

    private static JvmMeasurement capture(Worker[] workers) {
      ThreadMXBean bean = ManagementFactory.getThreadMXBean();
      boolean monitorSupported = bean.isThreadContentionMonitoringSupported();
      if (monitorSupported && !bean.isThreadContentionMonitoringEnabled()) {
        bean.setThreadContentionMonitoringEnabled(true);
      }
      com.sun.management.ThreadMXBean allocation = bean instanceof com.sun.management.ThreadMXBean extended
          && extended.isThreadAllocatedMemorySupported() ? extended : null;
      long processCpu = -1;
      java.lang.management.OperatingSystemMXBean operatingSystem =
          ManagementFactory.getOperatingSystemMXBean();
      if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
        processCpu = extended.getProcessCpuTime();
      }
      long allocated = allocation == null ? -1 : allocation.getTotalThreadAllocatedBytes();
      long blockedMillis = 0;
      boolean blockedAvailable = monitorSupported;
      Thread[] liveThreads = Thread.getAllStackTraces().keySet().toArray(new Thread[0]);
      long[] ids = new long[liveThreads.length];
      for (int index = 0; index < liveThreads.length; index++) {
        ids[index] = liveThreads[index].threadId();
      }
      Arrays.sort(ids);
      ThreadInfo[] infos = monitorSupported ? bean.getThreadInfo(ids) : null;
      if (blockedAvailable) {
        for (ThreadInfo info : infos) {
          long value = info == null ? -1 : info.getBlockedTime();
          if (value < 0) {
            blockedAvailable = false;
            break;
          }
          blockedMillis += value;
        }
      }
      long collections = 0;
      long millis = 0;
      for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
        if (collector.getCollectionCount() >= 0) collections += collector.getCollectionCount();
        if (collector.getCollectionTime() >= 0) millis += collector.getCollectionTime();
      }
      return new JvmMeasurement(
          processCpu >= 0 && allocated >= 0,
          processCpu,
          blockedAvailable ? blockedMillis * 1_000_000L : -1,
          allocated,
          collections,
          millis,
          ids);
    }
  }

  private enum Mode { CORRECTNESS, PERFORMANCE }

  private enum Operation { ALLOWED_READ, DENIED_WRITE, PREPARED_READ, CONDITIONAL_READ }

  private static final class Worker {
    private final int clientId;
    private final Arguments configuration;
    private final LoopbackRiverServer server;
    private final SSLContext clientContext;
    private final byte[] token;
    private final long[] operationCounts = new long[Operation.values().length];
    private final long[] statusCounts = new long[StatusCode.values().length];
    private final Histogram latency = new Histogram(120_000_000_000L, 3);
    private Observation[] observations = new Observation[0];
    private RiverClientConnection client;
    private RiverSession session;
    private PreparedOpenResult programProbe;
    private PreparedOpenResult programRead;
    private PreparedOpenResult perRequest;
    private ProgramOpenResult programOpen;
    private TransactionProgram program;
    private ParameterSet parameters;
    private TransactionProgramArguments programArguments;
    private CommandResult command;
    private QueryOpenResult queryOpen;
    private RowResult row;
    private TransactionProgramResult programResult;
    private Thread thread;
    private CountDownLatch startLatch;
    private CountDownLatch warmupStart;
    private CountDownLatch warmupDone;
    private CountDownLatch measuredStart;
    private CountDownLatch measuredDone;
    private CountDownLatch measurementRelease;
    private long warmupDeadline;
    private long measuredDeadline;
    private long issued;
    private long completed;
    private long expectedDecisions;
    private long latencyCount;
    private long latencyTotal;
    private long latencyMaximum;
    private String failure;
    private boolean operationValid;

    private Worker(int clientId, Arguments configuration, LoopbackRiverServer server,
        SSLContext clientContext, byte[] token) {
      this.clientId = clientId;
      this.configuration = configuration;
      this.server = server;
      this.clientContext = clientContext;
      this.token = token;
      if (configuration.mode == Mode.CORRECTNESS) {
        observations = new Observation[configuration.requestsPerClient];
        for (int index = 0; index < observations.length; index++) {
          observations[index] = new Observation();
        }
      }
    }

    private void open() {
      RiverClientOpenResult clientResult = new RiverClientOpenResult();
      require(
          RiverClientConnection.connectAuthenticatedLoopback(
              server.port(), clientContext, token, token.length, clientResult),
          "connect authenticated client " + clientId);
      client = clientResult.connection();
      SessionOpenResult sessionResult = new SessionOpenResult();
      require(client.createSession(sessionResult), "open client session " + clientId);
      session = sessionResult.session();
      programProbe = new PreparedOpenResult();
      programRead = new PreparedOpenResult();
      perRequest = new PreparedOpenResult();
      require(session.prepare(PROGRAM_PROBE, programProbe), "prepare conditional probe");
      require(session.prepare(PREPARED_READ, programRead), "prepare program read");
      program = conditionalProgram(programProbe.handle(), programRead.handle());
      programOpen = new ProgramOpenResult();
      require(session.prepareProgram(program, programOpen), "prepare conditional program");
      parameters = new ParameterSet(1, 0);
      programArguments = new TransactionProgramArguments();
      command = new CommandResult();
      queryOpen = new QueryOpenResult();
      row = new RowResult();
      programResult = new TransactionProgramResult();
    }

    private void runFixed() {
      try {
        startLatch.await();
        for (int ordinal = 0; ordinal < configuration.requestsPerClient; ordinal++) {
          perform(ordinal, true);
        }
      } catch (Exception failure) {
        this.failure = failure.toString();
      }
    }

    private void runTimed() {
      try {
        warmupStart.await();
        long ordinal = 0;
        while (System.nanoTime() < warmupDeadline) perform(ordinal++, false);
        warmupDone.countDown();
        measuredStart.await();
        ordinal = 0;
        while (System.nanoTime() < measuredDeadline) perform(ordinal++, false);
        measuredDone.countDown();
        measurementRelease.await();
      } catch (Exception failure) {
        this.failure = failure.toString();
        if (warmupDone != null) warmupDone.countDown();
        if (measuredDone != null) measuredDone.countDown();
      }
    }

    private void resetMeasuredCounters() {
      issued = 0;
      completed = 0;
      expectedDecisions = 0;
      latencyCount = 0;
      latencyTotal = 0;
      latencyMaximum = 0;
      latency.reset();
      Arrays.fill(operationCounts, 0);
      Arrays.fill(statusCounts, 0);
    }

    private void perform(long ordinal, boolean retainObservation) {
      Operation operation = operation(configuration.seed, clientId, ordinal);
      long requestId = ((long) clientId << 32) ^ ordinal;
      long started = System.nanoTime();
      StatusCode status;
      long decisions;
      boolean admitted;
      operationValid = true;
      switch (operation) {
        case ALLOWED_READ -> {
          status = readQuery(PLAIN_READ);
          decisions = 1;
          admitted = status.isOk();
          operationValid = status == StatusCode.OK && row.isAvailable() && row.bigintAt(0) == 1;
        }
        case DENIED_WRITE -> {
          status = session.execute(DENIED_WRITE, command);
          decisions = 1;
          admitted = false;
          operationValid = status == StatusCode.ACCESS_DENIED;
        }
        case PREPARED_READ -> {
          status = session.prepare(PREPARED_READ, perRequest);
          decisions = 1;
          if (status.isOk()) {
            parameters.reset();
            status = parameters.appendBigint(1);
            if (status.isOk()) {
              status = session.beginPreparedQuery(perRequest.handle(), parameters, queryOpen);
              decisions++;
              if (status.isOk()) {
                RiverQuery query = queryOpen.query();
                status = query.next(row);
                if (status.isOk() && (!row.isAvailable() || row.bigintAt(0) != 1)) {
                  operationValid = false;
                }
                StatusCode closeQuery = query.close(command);
                if (status.isOk()) status = closeQuery;
              }
            }
            StatusCode close = session.closePrepared(perRequest.handle());
            if (status.isOk()) status = close;
          }
          admitted = status.isOk();
          operationValid &= status == StatusCode.OK;
        }
        case CONDITIONAL_READ -> {
          long id = ((mix(configuration.seed, clientId, ordinal) & 1L) == 0) ? 1 : 9999;
          programArguments.reset();
          status = programArguments.setFixed(0, SqlTypeDescriptor.BIGINT, id);
          if (status.isOk()) {
            status = session.executeProgram(
                programOpen.handle(),
                IsolationLevel.READ_COMMITTED,
                programArguments,
                programResult);
          }
          decisions = id == 1 ? 2 : 1;
          admitted = status.isOk();
          operationValid = status == StatusCode.OK;
          if (status.isOk()) {
            operationValid = id == 1
                ? programResult.stepCount() == 2
                    && programResult.programStep(0) == 0
                    && programResult.programStep(1) == 1
                    && programResult.rowCount(0) == 1
                    && programResult.rowCount(1) == 1
                    && programResult.valueAt(programResult.firstRow(0), 0) == 1
                    && programResult.valueAt(programResult.firstRow(1), 0) == 1
                : programResult.stepCount() == 1
                    && programResult.programStep(0) == 0
                    && programResult.rowCount(0) == 0;
          }
        }
        default -> throw new IllegalStateException("unknown operation");
      }
      long elapsed = System.nanoTime() - started;
      issued++;
      completed++;
      expectedDecisions += decisions;
      operationCounts[operation.ordinal()]++;
      statusCounts[status.ordinal()]++;
      latencyCount++;
      latencyTotal += elapsed;
      latencyMaximum = Math.max(latencyMaximum, elapsed);
      latency.recordValue(elapsed);
      if (retainObservation) {
        Observation observation = observations[(int) ordinal];
        observation.requestId = requestId;
        observation.clientId = clientId;
        observation.ordinal = ordinal;
        observation.operation = operation.ordinal();
        observation.status = status.ordinal();
        observation.admitted = admitted;
        observation.latencyNanos = elapsed;
      }
      if (!operationValid) {
        String detail = operation == Operation.CONDITIONAL_READ
            ? " conditional_step_count=" + programResult.stepCount()
                + " program_step_0=" + programResult.programStep(0)
                + " row_count_0=" + programResult.rowCount(0)
                + " first_row_0=" + programResult.firstRow(0)
                + " conditional_id="
                + (((mix(configuration.seed, clientId, ordinal) & 1L) == 0) ? 1 : 9999)
            : "";
        throw new RunFailure(
            "unexpected " + operation + " result: " + status
                + " client=" + clientId + " ordinal=" + ordinal + detail);
      }
    }

    private StatusCode readQuery(String sql) {
      queryOpen.reset();
      StatusCode status = session.beginQuery(sql, queryOpen);
      if (!status.isOk()) return status;
      RiverQuery query = queryOpen.query();
      status = query.next(row);
      StatusCode close = query.close(command);
      return status.isOk() ? close : status;
    }

    private StatusCode close() {
      if (client == null) return StatusCode.CLOSED;
      StatusCode status = session == null ? StatusCode.OK : session.close();
      StatusCode clientStatus = client.close();
      client = null;
      return status.isOk() ? clientStatus : status;
    }
  }

  private static TransactionProgram conditionalProgram(long probeHandle, long readHandle) {
    TransactionProgram program = new TransactionProgram();
    require(program.beginStep(probeHandle, TransactionProgramAction.ZERO_OR_ONE), "program step 0");
    require(program.beginParameter(), "program parameter 0");
    require(program.argument(0, SqlTypeDescriptor.BIGINT), "program argument 0");
    require(program.endExpression(), "program expression 0");
    require(program.captureColumn(0), "program capture column 0");
    require(program.skipOnEmpty(2), "program empty branch");
    require(program.endStep(), "program end step 0");
    require(program.beginStep(readHandle, TransactionProgramAction.EXACT_ONE), "program step 1");
    require(program.beginParameter(), "program parameter 1");
    require(program.priorResult(0, 0, SqlTypeDescriptor.BIGINT), "program prior result");
    require(program.endExpression(), "program expression 1");
    require(program.captureColumn(0), "program capture column 1");
    require(program.endStep(), "program end step 1");
    require(program.freeze(), "freeze conditional program");
    return program;
  }

  private static Operation operation(long seed, int client, long ordinal) {
    long value = mix(seed, client, ordinal) & 0xffffL;
    if (value < 29_491) return Operation.ALLOWED_READ;
    if (value < 42_598) return Operation.DENIED_WRITE;
    if (value < 55_705) return Operation.PREPARED_READ;
    return Operation.CONDITIONAL_READ;
  }

  private static long mix(long seed, int client, long ordinal) {
    long value = seed + 0x9e3779b97f4a7c15L * (ordinal + 1);
    value ^= 0x632be59bd9b4e019L * (client + 1L);
    value ^= value >>> 30;
    value *= 0xbf58476d1ce4e5b9L;
    value ^= value >>> 27;
    value *= 0x94d049bb133111ebL;
    return value ^ value >>> 31;
  }

  private static final class Observation {
    private long requestId;
    private int clientId;
    private long ordinal;
    private int operation;
    private int status;
    private boolean admitted;
    private long latencyNanos;
  }

  private static final class Arguments {
    private Mode mode = Mode.CORRECTNESS;
    private int clients = 16;
    private int requestsPerClient = 10_000;
    private long seed = 410_221;
    private int warmupSeconds = 5;
    private int measuredSeconds = 30;
    private Path outputDirectory;

    private static Arguments parse(String[] args) {
      Arguments parsed = new Arguments();
      for (String argument : args) {
        int separator = argument.indexOf('=');
        if (separator <= 2 || separator == argument.length() - 1 || !argument.startsWith("--")) {
          return null;
        }
        String key = argument.substring(2, separator);
        String value = argument.substring(separator + 1);
        try {
          switch (key) {
            case "mode" -> parsed.mode = Mode.valueOf(value.toUpperCase());
            case "clients" -> parsed.clients = positiveInt(value);
            case "requests-per-client" -> parsed.requestsPerClient = positiveInt(value);
            case "seed" -> parsed.seed = Long.parseLong(value);
            case "warmup-seconds" -> parsed.warmupSeconds = nonNegativeInt(value);
            case "measured-seconds" -> parsed.measuredSeconds = positiveInt(value);
            case "output-dir" -> parsed.outputDirectory = Path.of(value);
            default -> { return null; }
          }
        } catch (RuntimeException invalid) {
          return null;
        }
      }
      if (parsed.outputDirectory == null || parsed.clients > LoopbackRiverServer.DEFAULT_MAXIMUM_CONNECTIONS
          || parsed.mode == Mode.CORRECTNESS && parsed.requestsPerClient <= 0
          || parsed.mode == Mode.PERFORMANCE && parsed.measuredSeconds <= 0) {
        return null;
      }
      return parsed;
    }

    private static int positiveInt(String value) {
      int parsed = Integer.parseInt(value);
      if (parsed <= 0) throw new IllegalArgumentException();
      return parsed;
    }

    private static int nonNegativeInt(String value) {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) throw new IllegalArgumentException();
      return parsed;
    }
  }

  private static final class RunFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private RunFailure(String message) { super(message); }
  }
}
