package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.jdbc.RiverTransactionDiagnostics;
import io.riverdb.server.app.GeneratedClientFileTestFixture;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

final class TpccConcurrencyReproducerTest {
  @RepeatedTest(2)
  void warehouseOrderPreservesMixedAndSerializableProgress(@TempDir Path root) throws Exception {
    for (int isolation : new int[] {Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE}) {
      for (int clients : new int[] {2, 3}) {
        Path directory = Files.createDirectories(root.resolve(isolation + "-" + clients));
        var owner = GeneratedClientFileTestFixture.open(directory,
            EmbeddedLockDiagnosticsConfig.bounded(1_048_576, 2, 16, 32, 1, 8));
        try {
          run(owner, isolation, clients);
        } finally {
          assertEquals(StatusCode.OK, owner.close());
        }
      }
    }
  }

  private static void run(GeneratedClientFileTestFixture owner, int isolation, int clients)
      throws Exception {
    String url = "jdbc:river:client-file:" + owner.clientFile();
    var config = TpccConfig.parse(new String[] {"--url=" + url, "--tiny=true", "--seed=42"});
    RiverDatabase database = owner.instance().database();
    try (Connection admin = DriverManager.getConnection(url);
        Connection paymentConnection = DriverManager.getConnection(url);
        Connection newOrderConnection = DriverManager.getConnection(url);
        Connection thirdConnection = DriverManager.getConnection(url)) {
      TpccSchema.create(admin);
      new TpccLoader(config).load(admin);
      TpccInvariants.verifyLoaded(admin, config);
      configure(paymentConnection, isolation, 101);
      configure(newOrderConnection, Connection.TRANSACTION_SERIALIZABLE, 102);
      configure(thirdConnection, isolation, 103);
      var gate = new TpccConcurrencyGate();
      try (var payment = new TpccPayment(gate.wrap(paymentConnection),
              paymentConnection.unwrap(RiverTransactionDiagnostics.class));
          var newOrder = new TpccRiverNewOrder(newOrderConnection, 1, config.itemCount());
          var third = new TpccPayment(thirdConnection,
              thirdConnection.unwrap(RiverTransactionDiagnostics.class))) {
        assertEquals(StatusCode.OK, EmbeddedRiver.beginPerformanceCapture(database));
        var workers = Executors.newFixedThreadPool(clients);
        try {
          Future<Boolean> first = workers.submit(() -> payment.execute(payment(1)));
          assertTrue(gate.held.await(5, TimeUnit.SECONDS), "Payment did not retain warehouse");
          Future<Boolean> second = workers.submit(() -> newOrder.execute(newOrder()));
          awaitWaiters(database, second, 1);
          Future<Boolean> last = null;
          if (clients == 3) {
            last = workers.submit(() -> third.execute(payment(2)));
            awaitWaiters(database, last, 2);
          }
          gate.release.countDown();
          assertTrue(first.get(5, TimeUnit.SECONDS));
          assertTrue(second.get(5, TimeUnit.SECONDS));
          if (last != null) assertTrue(last.get(5, TimeUnit.SECONDS));
        } finally {
          gate.release.countDown();
          workers.shutdownNow();
          assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "transaction workers did not join");
        }
      }
      TpccInvariants.verifyBusiness(admin, config);
      assertEquals(0, database.activeTransactionCount());
      assertEquals(0, database.retainedSnapshotCount());
      assertEquals(0, database.activeLockCount());
      assertEquals(0, database.waitingLockCount());
      assertEquals(0, database.lockWaitsTimedOut());
      var diagnostics = new StringBuilder();
      assertEquals(StatusCode.OK, EmbeddedRiver.appendDeadlockDiagnostics(database, diagnostics));
      assertTrue(diagnostics.toString().contains("server_deadlock_victim_selections=0\n"));
      assertTrue(diagnostics.toString().contains("server_deadlock_diagnostics_valid=true\n"));
      var capture = new StringBuilder();
      assertEquals(StatusCode.OK, EmbeddedRiver.endPerformanceCapture(database, capture));
      assertTrue(capture.toString().contains("server_capture_lock_block_valid=true\n"));
      var values = new java.util.Properties();
      values.load(new java.io.StringReader(capture.toString()));
      for (String counter : java.util.List.of("block_overflows", "block_unclassified",
          "blocked_cancelled", "blocked_deadlocked", "blocked_timed_out", "blocked_failed",
          "block_failed", "block_revoked_after_handoff", "block_victim_selections")) {
        assertEquals("0", values.getProperty("server_capture_lock_" + counter), counter);
      }
      assertEquals("" + (clients - 1), values.getProperty("server_capture_lock_blocked_consumed"));
      int buckets = Integer.parseInt(values.getProperty("server_capture_lock_block_bucket_count"));
      boolean warehouseRead = false;
      for (int i = 0; i < buckets; i++) {
        String prefix = "server_capture_lock_block_bucket_" + i + "_";
        if ("SHARED".equals(values.getProperty(prefix + "requested_mode"))) {
          assertEquals(isolation == Connection.TRANSACTION_REPEATABLE_READ ? "KEY" : "TUPLE_RANGE",
              values.getProperty(prefix + "scope"));
          assertEquals("EXCLUSIVE", values.getProperty(prefix + "blocker_mode"));
          assertEquals("ORDINARY", values.getProperty(prefix + "waiter_queue"));
          assertEquals("ACTIVE_OWNER", values.getProperty(prefix + "blocker_queue"));
          assertEquals("ACTIVE_OWNER", values.getProperty(prefix + "relationship"));
          assertEquals("NO_INCOMPATIBLE_ACTIVE_OWNER", values.getProperty(prefix + "grant_precondition"));
          assertEquals("1", values.getProperty(prefix + "count"));
          warehouseRead = true;
        }
      }
      assertTrue(warehouseRead, capture.toString());
      diagnostics.toString().lines().filter(line -> line.contains("_overflows="))
          .forEach(line -> assertTrue(line.endsWith("=0"), line));
      assertTrue(diagnostics.toString().contains("server_deadlock_victim_outcomes=0\n"));
      assertTrue(diagnostics.toString().contains("server_deadlock_queued_requests_cancelled=0\n"));
      System.out.println("Payment isolation=" + isolation + " NewOrder=SERIALIZABLE clients="
          + clients + " committed=" + clients + " retries=0\n" + diagnostics + capture);
      // The same physical victim-capable sessions remain usable after all queued work drains.
      paymentConnection.rollback();
      thirdConnection.rollback();
      try (var statement = paymentConnection.createStatement();
          var rows = statement.executeQuery("SELECT w_id FROM warehouse WHERE w_id=1")) {
        assertTrue(rows.next());
        assertEquals(1, rows.getInt(1));
      }
      paymentConnection.commit();
    }
  }

  private static void configure(Connection connection, int isolation, long tag) throws Exception {
    connection.setAutoCommit(false);
    connection.setTransactionIsolation(isolation);
    assertEquals(isolation, connection.getTransactionIsolation());
    connection.unwrap(RiverTransactionDiagnostics.class).beginDiagnosticAttempt(tag, 1);
  }

  private static void awaitWaiters(RiverDatabase database, Future<?> pending, int count) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (database.waitingLockCount() != count && !pending.isDone()
        && System.nanoTime() < deadline) Thread.onSpinWait();
    assertFalse(pending.isDone());
    assertEquals(count, database.waitingLockCount());
  }

  private static TpccInputs.Payment payment(int district) {
    var input = new TpccInputs.Payment();
    input.warehouse = input.customerWarehouse = 1;
    input.district = input.customerDistrict = district;
    input.customer = 1;
    input.amount = new BigDecimal("12.34");
    input.date = new Timestamp(1);
    return input;
  }

  private static TpccInputs.NewOrder newOrder() {
    var input = new TpccInputs.NewOrder();
    input.warehouse = input.district = input.customer = 1;
    input.lines = 5;
    input.entry = new Timestamp(1);
    for (int i = 0; i < input.lines; i++) {
      input.item[i] = i + 1;
      input.quantity[i] = 1;
      input.supplyWarehouse[i] = 1;
    }
    return input;
  }
}
