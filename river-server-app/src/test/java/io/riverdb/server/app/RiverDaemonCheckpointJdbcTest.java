package io.riverdb.server.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.jdbc.RiverConnectionMetrics;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.server.LoopbackServerLimits;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real JDBC CHECKPOINT failures and orderly managed-instance recovery. */
final class RiverDaemonCheckpointJdbcTest {
  private static final DatabaseIncarnation INCARNATION = DatabaseIncarnation.of(701, 709);
  private static final long CLIENT_READ_TIMEOUT_SECONDS = 30;

  @Test
  void returnedCheckpointIoFailureCanBeClosedAfterStorageRecoversAndReopened(
      @TempDir Path root) throws Exception {
    Path datadir = root.toRealPath().resolve("instance");
    runChild("returned", datadir, 20);
    RiverDaemonInstance reopened = restart(datadir, filesystem());
    try {
      assertRow(reopened);
    } finally {
      closeIfOpen(reopened);
    }
  }

  @Test
  void heldCheckpointWriteTimesOutAtJdbcClientThenServerClosesAndReopens(
      @TempDir Path root) throws Exception {
    Path datadir = root.toRealPath().resolve("instance");
    runChild("held", datadir, 70);
    RiverDaemonInstance reopened = restart(datadir, filesystem());
    try {
      assertRow(reopened);
    } finally {
      closeIfOpen(reopened);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !"--child".equals(args[0])) {
      throw new IllegalArgumentException("expected --child <scenario> <datadir>");
    }
    Path datadir = Path.of(args[2]);
    switch (args[1]) {
      case "returned" -> runReturnedFailureScenario(datadir);
      case "held" -> runHeldWriteScenario(datadir);
      default -> throw new IllegalArgumentException("unknown scenario: " + args[1]);
    }
  }

  private static void runReturnedFailureScenario(Path datadir) throws Exception {
    RiverDaemonInstance instance = openFirst(datadir, filesystem());
    CheckpointPageFile pageFile = null;
    try {
      try (Connection connection = connect(instance)) {
        createRow(connection);
        connection.setAutoCommit(true);
        pageFile = interceptCheckpointPageFile(instance);
        pageFile.failWrites.set(true);
        RiverConnectionMetrics metrics = connection.unwrap(RiverConnectionMetrics.class);
        long completedBefore = metrics.completedRequests();
        SQLException failure = checkpointFailure(connection);
        assertTrue(failure.getMessage().contains(StatusCode.IO_FAILURE.name()),
            failure.getMessage());
        assertTrue(pageFile.failedWrites.get() > 0);
        assertEquals(completedBefore + 1, metrics.completedRequests());
      }

      // The same durable error remains active during close, so cleanup must remain retryable.
      assertEquals(StatusCode.IO_FAILURE, instance.close());
      assertFalse(instance.servicesClosed());
      pageFile.failWrites.set(false);
      assertEquals(StatusCode.OK, instance.close());
      assertTrue(instance.servicesClosed());
    } finally {
      if (pageFile != null) pageFile.failWrites.set(false);
      closeIfOpen(instance);
    }
  }

  private static void runHeldWriteScenario(Path datadir) throws Exception {
    RiverDaemonInstance instance = openFirst(datadir, filesystem());
    CheckpointPageFile pageFile = null;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Connection connection = null;
    try {
      connection = connect(instance);
      createRow(connection);
      connection.setAutoCommit(true);
      pageFile = interceptCheckpointPageFile(instance);
      pageFile.holdFirstWrite.set(true);
      RiverConnectionMetrics metrics = connection.unwrap(RiverConnectionMetrics.class);
      long completedBefore = metrics.completedRequests();
      long started = System.nanoTime();
      Connection checkpointConnection = connection;
      Future<SQLException> checkpoint = executor.submit(
          () -> checkpointFailure(checkpointConnection));
      assertTrue(pageFile.entered.await(5, TimeUnit.SECONDS),
          "CHECKPOINT did not reach the live page-file write");

      SQLException failure = checkpoint.get(35, TimeUnit.SECONDS);
      long elapsedNanos = System.nanoTime() - started;
      assertTrue(failure.getMessage().contains(StatusCode.IO_FAILURE.name()),
          failure.getMessage());
      assertTrue(elapsedNanos
          >= TimeUnit.SECONDS.toNanos(CLIENT_READ_TIMEOUT_SECONDS - 1));
      assertEquals(completedBefore, metrics.completedRequests(),
          "a timed-out client must not be counted as a completed server request");
      assertEquals(1, pageFile.heldWrites.get());
      System.out.println("CHECKPOINT JDBC returned after "
          + TimeUnit.NANOSECONDS.toMillis(elapsedNanos) + " ms");

      pageFile.release.countDown();
      connection.close();
      try (Connection recoveredConnection = connect(instance)) {
        assertRow(recoveredConnection);
      }
      assertEquals(StatusCode.OK, instance.close());
      assertTrue(instance.servicesClosed());
    } finally {
      if (pageFile != null) pageFile.release.countDown();
      try {
        if (connection != null) connection.close();
      } finally {
        executor.shutdownNow();
        try {
          if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new AssertionError("JDBC checkpoint worker did not stop after release");
          }
        } finally {
          closeIfOpen(instance);
        }
      }
    }
  }

  private static void runChild(String scenario, Path datadir, long timeoutSeconds)
      throws Exception {
    Path log = datadir.getParent().resolve(scenario + "-child.log");
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    Process process = new ProcessBuilder(
        java, "--enable-native-access=ALL-UNNAMED", "-Xmx1g",
        "-Duser.home=" + datadir.getParent(), "-cp", childClasspath(),
        RiverDaemonCheckpointJdbcTest.class.getName(), "--child", scenario, datadir.toString())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
    boolean exited;
    try {
      exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    } finally {
      finish(process);
    }
    String output = Files.readString(log, StandardCharsets.UTF_8);
    if ("held".equals(scenario)) System.out.print(output);
    assertTrue(exited, "child scenario timed out: " + scenario + "\n" + output);
    assertEquals(0, process.exitValue(), "child scenario failed: " + scenario + "\n" + output);
  }

  private static void finish(Process process) throws InterruptedException {
    if (!process.isAlive()) return;
    process.destroy();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child process could not be reaped");
    }
  }

  private static String childClasspath() {
    return System.getProperty("river.test.classpath", System.getProperty("java.class.path"));
  }

  private static RiverDaemonFileSystemResult filesystem() {
    RiverDaemonFileSystemResult result = new RiverDaemonFileSystemResult();
    assertEquals(StatusCode.OK, RiverDaemonFileSystems.current(result));
    return result;
  }

  private static RiverDaemonInstance openFirst(
      Path datadir, RiverDaemonFileSystemResult filesystem) throws Exception {
    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    assertEquals(StatusCode.OK, RiverDaemonInstance.open(
        datadir,
        filesystem.fileSystem(),
        new SecureRandom(),
        INCARNATION,
        "127.0.0.1",
        InetAddress.getByName("127.0.0.1"),
        0,
        LoopbackServerLimits.defaults(8),
        resourcePlan(),
        EmbeddedLockDiagnosticsConfig.disabled(),
        8,
        opened));
    assertNotNull(opened.instance());
    return opened.instance();
  }

  private static RiverDaemonInstance restart(
      Path datadir, RiverDaemonFileSystemResult filesystem) throws Exception {
    SecureRandom random = new SecureRandom();
    RiverDaemonInstance.RestartPreparation preparation =
        new RiverDaemonInstance.RestartPreparation();
    assertEquals(StatusCode.OK, RiverDaemonInstance.prepareRestart(
        datadir,
        filesystem.fileSystem(),
        random,
        resourcePlan(),
        EmbeddedLockDiagnosticsConfig.disabled(),
        8,
        preparation));
    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    assertEquals(StatusCode.OK, RiverDaemonInstance.openPreparedRestart(
        preparation,
        random,
        "127.0.0.1",
        InetAddress.getByName("127.0.0.1"),
        0,
        LoopbackServerLimits.defaults(8),
        opened));
    assertNotNull(opened.instance());
    return opened.instance();
  }

  private static DatabaseResourcePlanRequest resourcePlan() {
    return new DatabaseResourcePlanRequest()
        .memory(256_000_000L, 0, 0, 0, 64_000_000L)
        .lockProviderBytes(8_000_000L)
        .versionWorkspaceBytes(8_000_000L)
        .indexedPageCache(32_000_000L, 8_000_000L)
        .capacity(8, Integer.MAX_VALUE, 800L, 64_000_000L)
        .maximumDelivery(Integer.MAX_VALUE, 800L, 64_000_000L);
  }

  private static Connection connect(RiverDaemonInstance instance) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:river:client-file:" + instance.clientConfiguration());
  }

  private static void createRow(Connection connection) throws SQLException {
    connection.setAutoCommit(false);
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TABLE checkpoint_probe (id BIGINT PRIMARY KEY, value BIGINT NOT NULL)");
      statement.executeUpdate("INSERT INTO checkpoint_probe VALUES (1, 709)");
    }
    connection.commit();
  }

  private static SQLException checkpointFailure(Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("CHECKPOINT");
      throw new AssertionError("CHECKPOINT unexpectedly succeeded");
    } catch (SQLException failure) {
      return failure;
    }
  }

  private static void assertRow(RiverDaemonInstance instance) throws SQLException {
    try (Connection connection = connect(instance)) {
      assertRow(connection);
    }
  }

  private static void assertRow(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT value FROM checkpoint_probe WHERE id=1")) {
      assertTrue(rows.next());
      assertEquals(709, rows.getLong(1));
      assertFalse(rows.next());
    }
  }

  private static CheckpointPageFile interceptCheckpointPageFile(
      RiverDaemonInstance instance) throws Exception {
    DurableFile original = checkpointFile(instance);
    CheckpointPageFile wrapped = new CheckpointPageFile(original);
    setField(checkpointCoordinator(instance), "file", wrapped);
    return wrapped;
  }

  private static DurableFile checkpointFile(RiverDaemonInstance instance) throws Exception {
    return (DurableFile) field(checkpointCoordinator(instance), "file");
  }

  private static Object checkpointCoordinator(RiverDaemonInstance instance) throws Exception {
    Object relational = field(instance.database(), "database");
    Object embedded = field(relational, "embedded");
    Object table = field(embedded, "table");
    Object store = field(table, "store");
    return field(store, "checkpoints");
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static void setField(Object owner, String name, Object value) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static void closeIfOpen(RiverDaemonInstance instance) {
    if (instance != null) {
      StatusCode status = instance.close();
      assertTrue(status == StatusCode.OK || status == StatusCode.CLOSED,
          "instance cleanup failed: " + status);
    }
  }

  private static final class CheckpointPageFile implements DurableFile {
    final DurableFile delegate;
    private final AtomicBoolean failWrites = new AtomicBoolean();
    private final AtomicInteger failedWrites = new AtomicInteger();
    private final AtomicBoolean holdFirstWrite = new AtomicBoolean();
    private final AtomicInteger heldWrites = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    CheckpointPageFile(DurableFile file) { delegate = file; }

    @Override public StatusCode read(long position, ByteBuffer target, IoResult result) {
      return delegate.read(position, target, result);
    }
    @Override public StatusCode write(long position, ByteBuffer source, IoResult result) {
      if (failWrites.get()) {
        failedWrites.incrementAndGet();
        return StatusCode.IO_FAILURE;
      }
      if (holdFirstWrite.compareAndSet(true, false)) {
        heldWrites.incrementAndGet();
        entered.countDown();
        try {
          if (!release.await(45, TimeUnit.SECONDS)) return StatusCode.IO_FAILURE;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return StatusCode.CANCELLED;
        }
      }
      return delegate.write(position, source, result);
    }
    @Override public StatusCode force(ForceMode mode) { return delegate.force(mode); }
    @Override public StatusCode force(long start, long end, ForceMode mode) {
      return delegate.force(start, end, mode);
    }
    @Override public StatusCode truncate(long sizeBytes) { return delegate.truncate(sizeBytes); }
    @Override public StatusCode size(FileSizeResult result) { return delegate.size(result); }
    @Override public StatusCode close() { return delegate.close(); }
  }
}
