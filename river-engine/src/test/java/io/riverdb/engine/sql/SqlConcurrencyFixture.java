package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** One real SQL database; scheduler gauges, rather than thread state, order pending requests. */
final class SqlConcurrencyFixture implements AutoCloseable {
  final RelationalDatabase database;
  final SqlSession[] sessions = new SqlSession[3];
  final ExecutorService workers = Executors.newFixedThreadPool(3);

  private final long[] steps = new long[3];
  private final long[] pendingSteps = new long[3];
  private int rollbacks;
  private boolean capturing;
  private final List<Map<String, String>> expectedBlocks = new ArrayList<>();

  SqlConcurrencyFixture(Path root) throws java.io.IOException {
    java.nio.file.Files.createDirectories(root);
    var opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(databaseRequest(8), root,
        DatabaseIncarnation.of(757, 761), WalGeneration.of(1), 8,
        EmbeddedLockDiagnosticsConfig.bounded(1_048_576, 2, 16, 32, 1, 8), opened));
    database = opened.database();
    try {
      for (int i = 0; i < sessions.length; i++) {
        var result = new SqlSessionOpenResult();
        assertEquals(StatusCode.OK, SqlSession.create(database, result));
        sessions[i] = result.session();
        assertEquals(StatusCode.OK, sessions[i].configureTransactionDiagnostics(i + 101, 1, 1));
      }
      exec(0, "CREATE TABLE rows (id BIGINT PRIMARY KEY, category BIGINT, value BIGINT)");
      exec(0, "CREATE INDEX by_category ON rows(category)");
      exec(0, "INSERT INTO rows VALUES (1,10,100),(2,20,200),(3,30,300)");
      assertEquals(StatusCode.OK, database.beginPerformanceCapture());
      capturing = true;
    } catch (RuntimeException | Error failure) {
      try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  StatusCode execute(int owner, String sql) {
    step(owner, sql);
    StatusCode status = sessions[owner].execute(sql, new SqlExecutionResult());
    if (status == StatusCode.DEADLOCK) pendingSteps[owner] = steps[owner];
    if (status == StatusCode.OK && sql.equals("ROLLBACK")) rollbacks++;
    return status;
  }

  void exec(int owner, String sql) { assertEquals(StatusCode.OK, execute(owner, sql), sql); }

  void begin(int owner, String isolation) {
    exec(owner, "BEGIN " + isolation);
    try {
      Object transactionSession = read(read(read(sessions[owner], "coordinator"), "session"), "session");
      var indexed = (io.riverdb.engine.table.IndexedTransactionSession) transactionSession;
      assertEquals(isolation.replace(' ', '_'), indexed.transaction().isolationLevel().name());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("cannot inspect effective transaction isolation", failure);
    }
  }

  // Read the real transaction owner without replacing factory initialization or exposing a runtime hook.
  static Object read(Object owner, String name) throws ReflectiveOperationException {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private void step(int owner, String sql) {
    assertEquals(StatusCode.OK, sessions[owner].configureTransactionDiagnostics(owner + 101, ++steps[owner], 1));
    System.out.println("attempt_tag=" + (owner + 101) + " step_tag=" + steps[owner] + " sql=" + sql);
  }

  Future<StatusCode> queue(int owner, String sql, long expectedWaiters) {
    pendingSteps[owner] = steps[owner] + 1;
    Future<StatusCode> future = workers.submit(() -> execute(owner, sql));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (database.waitingLockCount() != expectedWaiters && !future.isDone()
        && System.nanoTime() < deadline) Thread.onSpinWait();
    assertFalse(future.isDone(), "request finished before expected scheduler admission: " + sql);
    assertEquals(expectedWaiters, database.waitingLockCount(), sql);
    return future;
  }

  void deadlock(int owner, String sql) throws Exception {
    assertEquals(StatusCode.DEADLOCK, workers.submit(() -> execute(owner, sql)).get(5, TimeUnit.SECONDS), sql);
  }

  void completed(Future<StatusCode> future) throws Exception {
    assertEquals(StatusCode.OK, future.get(5, TimeUnit.SECONDS));
  }

  long scalar(int owner, String sql) {
    step(owner, sql);
    var result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, sessions[owner].execute(sql, result), sql);
    return result.value();
  }

  List<Long> scan(int owner, String sql) {
    return scan(owner, sql, 0);
  }

  void secondaryPlan(String query) {
    var operators = scan(0, "EXPLAIN " + query);
    var details = scan(0, "EXPLAIN " + query, 1);
    int index = operators.indexOf(io.riverdb.base.text.PackedText.pack("index"));
    assertTrue(index >= 0);
    assertEquals(1L, details.get(index), "secondary index ordinal 1");
  }

  private List<Long> scan(int owner, String sql, int column) {
    step(owner, sql);
    var cursor = new SqlScanCursor();
    var row = new SqlScanRowResult();
    var values = new ArrayList<Long>();
    assertEquals(StatusCode.OK, sessions[owner].beginScan(sql, cursor));
    try {
      StatusCode status;
      while ((status = sessions[owner].nextScan(cursor, row)) == StatusCode.OK) {
        values.add(row.valueAt(column));
      }
      assertEquals(StatusCode.CONFLICT, status);
    } finally {
      assertEquals(StatusCode.OK, sessions[owner].closeScan(cursor, new SqlExecutionResult()));
    }
    return values;
  }

  String diagnostics() {
    var text = new StringBuilder();
    assertEquals(StatusCode.OK, database.appendDeadlockDiagnostics(text));
    return text.toString();
  }

  record Edge(int waiter, int blocker, String scope, String requested, String held,
      String queue, long namespace, long key) {}

  void cycle(int victim, Edge... expected) {
    SqlConcurrencyDiagnostics.cycle(database, diagnostics(), rollbacks, pendingSteps, victim, expected);
  }

  void noVictim() {
    String text = diagnostics();
    SqlConcurrencyDiagnostics.validDiagnostics(text);
    for (String counter : List.of("victim_selections", "victim_outcomes", "queued_requests_cancelled", "holdings_released")) {
      assertTrue(text.contains("server_deadlock_" + counter + "=0\n"), text);
    }
    assertEquals(0, rollbacks);
  }

  void block(String scope, String requested, String held, String queue, String blockerQueue,
      String relationship, String precondition) {
    expectedBlocks.add(Map.of("scope", scope, "requested_mode", requested, "blocker_mode", held,
        "waiter_queue", queue, "blocker_queue", blockerQueue, "relationship", relationship,
        "grant_precondition", precondition));
  }

  private void checkCapture(String text) throws java.io.IOException {
    var values = new java.util.Properties();
    values.load(new java.io.StringReader(text));
    assertEquals("true", values.getProperty("server_capture_lock_block_valid"));
    for (String name : List.of("block_overflows", "block_unclassified", "blocked_timed_out",
        "blocked_failed", "block_failed", "block_revoked_after_handoff")) {
      assertEquals("0", values.getProperty("server_capture_lock_" + name), name);
    }
    if (rollbacks == 0) {
      assertEquals("0", values.getProperty("server_capture_lock_blocked_cancelled"));
      assertEquals("0", values.getProperty("server_capture_lock_blocked_deadlocked"));
    }
    int count = Integer.parseInt(values.getProperty("server_capture_lock_block_bucket_count"));
    for (var expected : expectedBlocks) {
      boolean found = false;
      for (int i = 0; i < count; i++) {
        String prefix = "server_capture_lock_block_bucket_" + i + "_";
        if (expected.entrySet().stream().allMatch(e -> e.getValue().equals(values.getProperty(prefix + e.getKey())))) {
          assertTrue(Long.parseLong(values.getProperty(prefix + "count")) > 0);
          found = true;
        }
      }
      assertTrue(found, expected + "\n" + text);
    }
  }

  @Override
  public void close() {
    var cleanup = new ArrayList<org.junit.jupiter.api.function.Executable>();
    workers.shutdownNow();
    cleanup.add(() -> assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "SQL workers did not join"));
    for (SqlSession session : sessions) {
      if (session != null) cleanup.add(() -> assertEquals(StatusCode.OK, session.close()));
    }
    cleanup.add(() -> assertEquals(0, database.activeTransactionCount()));
    cleanup.add(() -> assertEquals(0, database.retainedSnapshotCount()));
    cleanup.add(() -> assertEquals(0, database.activeLockCount()));
    cleanup.add(() -> assertEquals(0, database.waitingLockCount()));
    cleanup.add(() -> assertEquals(0, database.lockWaitsTimedOut()));
    if (capturing) cleanup.add(() -> {
      var capture = new StringBuilder();
      assertEquals(StatusCode.OK, database.endPerformanceCapture(capture));
      capturing = false;
      System.out.println(diagnostics() + capture);
      checkCapture(capture.toString());
    });
    cleanup.add(() -> assertEquals(StatusCode.OK, database.close()));
    assertAll("all SQL owners released", cleanup);
  }
}
