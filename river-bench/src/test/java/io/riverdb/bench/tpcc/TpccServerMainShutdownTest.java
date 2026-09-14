package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.app.GeneratedClientFileTestFixture;
import io.riverdb.server.app.RiverDaemonInstance;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TpccServerMainShutdownTest {
  @Test
  void closesInstanceWhenTraceDumpFails(@TempDir Path root) throws Exception {
    GeneratedClientFileTestFixture fixture = GeneratedClientFileTestFixture.open(root);
    RiverDaemonInstance instance = fixture.instance();
    Path traceFile = root.resolve("trace.jfr");
    TpccTraceRecording recording = null;
    try {
      recording = TpccTraceRecording.start(traceFile, "shutdown-test");
      Files.createDirectory(traceFile);

      TpccTraceRecording activeRecording = recording;
      assertThrows(IOException.class, () -> TpccServerMain.shutdown(
          instance, activeRecording, null, instance.server().maximumConnections(),
          new TpccPerformanceCapture.ServerResult(false, StatusCode.OK, "")));
      assertTrue(instance.servicesClosed());
    } finally {
      if (!instance.servicesClosed()) {
        try {
          if (recording != null) recording.close();
        } finally {
          fixture.close();
        }
      }
    }
  }

  @Test
  void closesAcceptedSocketBeforeMetricsWaitForTransactionManager(@TempDir Path root)
      throws Exception {
    GeneratedClientFileTestFixture fixture = GeneratedClientFileTestFixture.open(root);
    RiverDaemonInstance instance = fixture.instance();
    LoopbackRiverServer server = instance.server();
    Connection connection = DriverManager.getConnection(
        "jdbc:river:client-file:" + fixture.clientFile());
    Thread monitorHolder = null;
    Thread shutdown = null;
    CountDownLatch releaseMonitor = new CountDownLatch(1);
    AtomicReference<Throwable> shutdownFailure = new AtomicReference<>();
    try {
      Socket acceptedSocket = awaitAcceptedSocket(server);
      Object transactionManager = transactionManager(instance);
      CountDownLatch monitorHeld = new CountDownLatch(1);
      monitorHolder = Thread.ofVirtual().start(() -> {
        synchronized (transactionManager) {
          monitorHeld.countDown();
          try {
            releaseMonitor.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      });
      assertTrue(monitorHeld.await(3, TimeUnit.SECONDS), "transaction manager monitor not held");

      Path metricsFile = root.resolve("server-metrics.txt");
      shutdown = Thread.ofVirtual().start(() -> {
        try {
          TpccServerMain.shutdown(
              instance, null, metricsFile, server.maximumConnections(),
              new TpccPerformanceCapture.ServerResult(false, StatusCode.OK, ""));
        } catch (Throwable failure) {
          shutdownFailure.set(failure);
        }
      });

      boolean socketClosedBeforeRelease = awaitSocketClosed(acceptedSocket);
      boolean shutdownStillWaiting = shutdown.isAlive();
      releaseMonitor.countDown();
      shutdown.join(TimeUnit.SECONDS.toMillis(5));
      monitorHolder.join(TimeUnit.SECONDS.toMillis(5));

      assertTrue(socketClosedBeforeRelease, "server socket remained open while metrics were blocked");
      assertTrue(shutdownStillWaiting, "shutdown unexpectedly passed the held metrics monitor");
      assertFalse(shutdown.isAlive(), "shutdown did not finish after releasing the monitor");
      assertFalse(monitorHolder.isAlive(), "monitor holder did not finish");
      assertNull(shutdownFailure.get());
      assertTrue(instance.servicesClosed());
      assertTrue(java.nio.file.Files.exists(metricsFile));
    } finally {
      releaseMonitor.countDown();
      if (shutdown != null) shutdown.join(TimeUnit.SECONDS.toMillis(5));
      if (monitorHolder != null) monitorHolder.join(TimeUnit.SECONDS.toMillis(5));
      connection.abort(Runnable::run);
      if (!instance.servicesClosed()) assertEquals(StatusCode.OK, fixture.close());
    }
  }

  private static Socket awaitAcceptedSocket(LoopbackRiverServer server) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      synchronized (server) {
        Object[] slots = (Object[]) field(server, "slots");
        for (Object slot : slots) {
          Socket socket = (Socket) field(slot, "socket");
          if (socket != null) return socket;
        }
      }
      Thread.sleep(1);
    }
    throw new AssertionError("no authenticated connection socket was admitted");
  }

  private static boolean awaitSocketClosed(Socket socket) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (socket.isClosed()) return true;
      Thread.sleep(1);
    }
    return socket.isClosed();
  }

  private static Object transactionManager(RiverDaemonInstance instance) throws Exception {
    Object engineDatabase = instance.database();
    Object relationalDatabase = field(engineDatabase, "database");
    Object embeddedDatabase = field(relationalDatabase, "embedded");
    Object manager = field(embeddedDatabase, "transactions");
    assertNotNull(manager);
    return manager;
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }
}
