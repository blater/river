package io.riverdb.bench.tpcc;

import io.riverdb.jdbc.RiverConnectionMetrics;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Runs and reports one prepared JDBC update transaction with JFR evidence. */
public final class TpccUpdateTraceMain {
  private static final String CREATE_TABLE =
      "CREATE TABLE trace_update (id INTEGER NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(id))";
  private static final String INSERT_ROW = "INSERT INTO trace_update VALUES (1,0)";
  private static final String UPDATE_ROW =
      "UPDATE trace_update SET value=value+? WHERE id=?";
  private static final java.lang.management.ThreadMXBean THREAD_BEAN =
      ManagementFactory.getThreadMXBean();
  private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN =
      THREAD_BEAN instanceof com.sun.management.ThreadMXBean extended ? extended : null;

  static {
    if (THREAD_BEAN.isCurrentThreadCpuTimeSupported()
        && !THREAD_BEAN.isThreadCpuTimeEnabled()) {
      THREAD_BEAN.setThreadCpuTimeEnabled(true);
    }
    if (ALLOCATION_BEAN != null
        && ALLOCATION_BEAN.isThreadAllocatedMemorySupported()
        && !ALLOCATION_BEAN.isThreadAllocatedMemoryEnabled()) {
      ALLOCATION_BEAN.setThreadAllocatedMemoryEnabled(true);
    }
  }

  private TpccUpdateTraceMain() {}

  @SuppressWarnings("try")
  public static void main(String[] arguments) throws Exception {
    Arguments configuration = Arguments.parse(arguments);
    Class.forName("io.riverdb.jdbc.RiverDriver");
    setup(configuration.url());
    warmup(configuration.url());
    System.out.println("setup=CREATE_TABLE+INSERT_ROW+3_ROLLBACK_WARMUPS excluded_from_trace=true");

    try (TpccTraceRecording recording = configuration.externalJfr() ? null
        : TpccTraceRecording.start(configuration.jfr(), "river-jdbc-single-update")) {
      signalAndAwaitServerTrace(configuration);
      State state = new State();
      runStep("jdbc.connect", state, () -> {
        state.connection = DriverManager.getConnection(configuration.url());
        state.metrics = state.connection.unwrap(RiverConnectionMetrics.class);
      });
      runStep("jdbc.set_auto_commit_false", state,
          () -> state.connection.setAutoCommit(false));
      runStep("jdbc.prepare_update", state,
          () -> state.statement = state.connection.prepareStatement(UPDATE_ROW));
      runStep("jdbc.bind_parameters", state, () -> {
        state.statement.setInt(1, 1);
        state.statement.setInt(2, 1);
      });
      runStep("jdbc.execute_update", state, () -> {
        int changed = state.statement.executeUpdate();
        if (changed != 1) throw new SQLException("expected one changed row, got " + changed);
      });
      runStep("jdbc.commit", state, () -> state.connection.commit());
      runStep("jdbc.close_prepared_statement", state, () -> {
        state.statement.close();
        state.statement = null;
      });
      runStep("jdbc.close_connection", state, () -> {
        state.connection.close();
        state.connection = null;
      });
      System.out.println("total_protocol_requests=" + state.metrics.completedRequests());
      System.out.println("total_bytes_sent=" + state.metrics.bytesSent());
      System.out.println("total_bytes_received=" + state.metrics.bytesReceived());
      System.out.println("trace_result=completed");
      System.out.println("trace_jfr=" + configuration.jfr().toAbsolutePath());
    }
  }

  private static void setup(String url) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(CREATE_TABLE);
      statement.executeUpdate(INSERT_ROW);
    }
  }

  private static void warmup(String url) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url);
        PreparedStatement statement = connection.prepareStatement(UPDATE_ROW)) {
      connection.setAutoCommit(false);
      for (int index = 0; index < 3; index++) {
        statement.setInt(1, 1);
        statement.setInt(2, 1);
        if (statement.executeUpdate() != 1) {
          throw new SQLException("warmup update did not change one row");
        }
        connection.rollback();
      }
    }
  }

  private static void signalAndAwaitServerTrace(Arguments configuration) throws Exception {
    Files.writeString(
        configuration.serverStartFile(), "start\n", StandardCharsets.US_ASCII,
        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!Files.exists(configuration.serverStartedFile())) {
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("managed server did not start its trace recording");
      }
      Thread.sleep(10);
    }
  }

  private static void runStep(String name, State state, StepOperation operation) throws Exception {
    TpccTraceStep event = new TpccTraceStep();
    event.step = name;
    long wallBefore = System.nanoTime();
    long cpuBefore = cpuNanos();
    long allocationBefore = allocatedBytes();
    long requestsBefore = state.metrics == null ? 0 : state.metrics.completedRequests();
    long sentBefore = state.metrics == null ? 0 : state.metrics.bytesSent();
    long receivedBefore = state.metrics == null ? 0 : state.metrics.bytesReceived();
    boolean success = false;
    try {
      operation.run();
      success = true;
    } finally {
      long wall = Math.max(0, System.nanoTime() - wallBefore);
      long cpu = cpuDelta(cpuBefore);
      long allocated = allocationDelta(allocationBefore);
      long nonCpu = cpu < 0 ? -1 : Math.max(0, wall - cpu);
      long requestsAfter = state.metrics == null ? 0 : state.metrics.completedRequests();
      long sentAfter = state.metrics == null ? 0 : state.metrics.bytesSent();
      long receivedAfter = state.metrics == null ? 0 : state.metrics.bytesReceived();
      event.outcome = success ? "ok" : "failed";
      event.wallNanos = wall;
      event.cpuNanos = cpu;
      event.nonCpuNanos = nonCpu;
      event.allocatedBytes = allocated;
      event.protocolRequests = Math.max(0, requestsAfter - requestsBefore);
      event.bytesSent = Math.max(0, sentAfter - sentBefore);
      event.bytesReceived = Math.max(0, receivedAfter - receivedBefore);
      event.commit();
      printStep(event);
    }
  }

  private static void printStep(TpccTraceStep event) {
    System.out.printf(
        Locale.ROOT,
        "step=%s outcome=%s wall_ms=%.3f cpu_ms=%s non_cpu_ms=%s allocated_bytes=%d"
            + " protocol_requests=%d bytes_sent=%d bytes_received=%d%n",
        event.step, event.outcome, event.wallNanos / 1_000_000.0,
        millis(event.cpuNanos), millis(event.nonCpuNanos), event.allocatedBytes,
        event.protocolRequests, event.bytesSent, event.bytesReceived);
  }

  private static String millis(long nanos) {
    return nanos < 0 ? "unavailable" : String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
  }

  private static long cpuNanos() {
    return THREAD_BEAN.isCurrentThreadCpuTimeSupported()
        ? THREAD_BEAN.getCurrentThreadCpuTime() : -1;
  }

  private static long cpuDelta(long before) {
    long after = cpuNanos();
    return before < 0 || after < before ? -1 : after - before;
  }

  private static long allocatedBytes() {
    return ALLOCATION_BEAN != null && ALLOCATION_BEAN.isThreadAllocatedMemorySupported()
        ? ALLOCATION_BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
  }

  private static long allocationDelta(long before) {
    long after = allocatedBytes();
    return before < 0 || after < before ? -1 : after - before;
  }

  private static final class State {
    Connection connection;
    PreparedStatement statement;
    RiverConnectionMetrics metrics;
  }

  @FunctionalInterface
  private interface StepOperation {
    void run() throws Exception;
  }

  private record Arguments(
      String url, Path jfr, Path serverStartFile, Path serverStartedFile,
      boolean externalJfr) {
    static Arguments parse(String[] arguments) {
      String url = null;
      Path jfr = null;
      Path serverStartFile = null;
      Path serverStartedFile = null;
      boolean externalJfr = false;
      for (String argument : arguments) {
        int equals = argument.indexOf('=');
        String key = equals < 0 ? argument : argument.substring(0, equals);
        String value = equals < 0 ? "" : argument.substring(equals + 1);
        switch (key) {
          case "--url" -> url = value;
          case "--jfr" -> jfr = Path.of(value);
          case "--server-start-file" -> serverStartFile = Path.of(value);
          case "--server-started-file" -> serverStartedFile = Path.of(value);
          case "--external-jfr" -> externalJfr = Boolean.parseBoolean(value);
          default -> throw new IllegalArgumentException("unknown argument: " + key);
        }
      }
      if (url == null || jfr == null || serverStartFile == null || serverStartedFile == null) {
        throw new IllegalArgumentException(
            "--url, --jfr, --server-start-file, and --server-started-file are required");
      }
      return new Arguments(url, jfr, serverStartFile, serverStartedFile, externalJfr);
    }
  }
}
