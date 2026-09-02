package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exact descriptor source candidates remain attached to one logical row while lock waits park. */
final class SqlDescriptorSourceCandidateTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x534f555243454341L, 0x4e44494441544553L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void repeatableReadDoesNotAdoptAKeyInsertedAfterItsSnapshot(@TempDir Path root) {
    Fixture fixture = open(root);
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "CREATE TABLE accounts (id BIGINT PRIMARY KEY,balance BIGINT)", fixture.result));
    assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN REPEATABLE READ", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "INSERT INTO accounts VALUES (1,100)", fixture.result));

    assertEquals(StatusCode.OK, fixture.waiter.execute(
        "UPDATE accounts SET balance=999 WHERE id=1", fixture.result));
    assertEquals(0, fixture.result.affectedRows());
    assertEquals(StatusCode.OK, fixture.waiter.execute(
        "DELETE FROM accounts WHERE id=1", fixture.result));
    assertEquals(0, fixture.result.affectedRows());
    assertEquals(StatusCode.CONFLICT, fixture.waiter.execute(
        "SELECT balance FROM accounts WHERE id=1 FOR UPDATE", fixture.result));
    assertEquals(StatusCode.OK, fixture.waiter.execute("ROLLBACK", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "SELECT balance FROM accounts WHERE id=1", fixture.result));
    assertEquals(100, fixture.result.value());
    fixture.close();
  }

  @Test
  void serializableDiscoversTheCurrentMappingOnlyAfterProtectingItsKey(@TempDir Path root) {
    Fixture fixture = open(root);
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "CREATE TABLE accounts (id BIGINT PRIMARY KEY,balance BIGINT)", fixture.result));
    assertEquals(StatusCode.OK, fixture.waiter.execute("BEGIN SERIALIZABLE", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "INSERT INTO accounts VALUES (1,100)", fixture.result));

    assertEquals(StatusCode.OK, fixture.waiter.execute(
        "SELECT balance FROM accounts WHERE id=1 FOR UPDATE", fixture.result));
    assertEquals(100, fixture.result.value());
    assertEquals(StatusCode.OK, fixture.waiter.execute("ROLLBACK", fixture.result));
    fixture.close();
  }

  @Test
  void queuedPrimaryReassignmentSkipsStaleMutationsAndRejectsMissingSelect(
      @TempDir Path root) throws Exception {
    assertReassignedPrimarySkipped(
        root.resolve("update"), "UPDATE accounts SET balance=999 WHERE id=1");
    assertReassignedPrimarySkipped(
        root.resolve("delete"), "DELETE FROM accounts WHERE id=1");
    assertReassignedPrimaryRejected(
        root.resolve("select"), "SELECT balance FROM accounts WHERE id=1 FOR UPDATE");
  }

  @Test
  void queuedDeleteAndReinsertDoesNotTransferTheOldSourceIdentity(
      @TempDir Path root) throws Exception {
    Fixture fixture = open(root);
    createAccount(fixture);
    beginMoverAndWaiter(fixture);
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "DELETE FROM accounts WHERE id=1", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "INSERT INTO accounts VALUES (1,200)", fixture.result));

    assertWaitSkippedAfterCommit(
        fixture, "UPDATE accounts SET balance=999 WHERE id=1");
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "SELECT balance FROM accounts WHERE id=1", fixture.result));
    assertEquals(200, fixture.result.value());
    fixture.close();
  }

  @Test
  void queuedCompositeUniqueReassignmentDoesNotMutateTheCurrentRow(
      @TempDir Path root) throws Exception {
    Fixture fixture = open(root);
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "CREATE TABLE accounts (id BIGINT PRIMARY KEY,tenant BIGINT,code BIGINT,"
            + "balance BIGINT,UNIQUE(tenant,code))", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "INSERT INTO accounts VALUES (1,10,20,100)", fixture.result));
    beginMoverAndWaiter(fixture);
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "UPDATE accounts SET tenant=11,code=21 WHERE id=1", fixture.result));

    assertWaitSkippedAfterCommit(
        fixture, "UPDATE accounts SET balance=999 WHERE tenant=10 AND code=20");
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "SELECT balance FROM accounts WHERE tenant=11 AND code=21", fixture.result));
    assertEquals(100, fixture.result.value());
    fixture.close();
  }

  private static void assertReassignedPrimaryRejected(Path root, String waitingSql)
      throws Exception {
    java.nio.file.Files.createDirectory(root);
    Fixture fixture = open(root);
    createAccount(fixture);
    beginMoverAndWaiter(fixture);
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "UPDATE accounts SET id=2 WHERE id=1", fixture.result));

    assertWaitRejectedAfterCommit(fixture, waitingSql);
    assertEquals(StatusCode.CONFLICT, fixture.mover.execute(
        "SELECT balance FROM accounts WHERE id=1", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "SELECT balance FROM accounts WHERE id=2", fixture.result));
    assertEquals(100, fixture.result.value());
    fixture.close();
  }

  private static void assertReassignedPrimarySkipped(Path root, String waitingSql)
      throws Exception {
    java.nio.file.Files.createDirectory(root);
    Fixture fixture = open(root);
    createAccount(fixture);
    beginMoverAndWaiter(fixture);
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "UPDATE accounts SET id=2 WHERE id=1", fixture.result));

    assertWaitSkippedAfterCommit(fixture, waitingSql);
    assertEquals(StatusCode.CONFLICT, fixture.mover.execute(
        "SELECT balance FROM accounts WHERE id=1", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "SELECT balance FROM accounts WHERE id=2", fixture.result));
    assertEquals(100, fixture.result.value());
    fixture.close();
  }

  private static void assertWaitRejectedAfterCommit(Fixture fixture, String sql)
      throws Exception {
    assertWaitAfterCommit(fixture, sql, StatusCode.CONFLICT);
  }

  private static void assertWaitSkippedAfterCommit(Fixture fixture, String sql)
      throws Exception {
    assertWaitAfterCommit(fixture, sql, StatusCode.OK);
  }

  private static void assertWaitAfterCommit(
      Fixture fixture, String sql, StatusCode expected) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1);
    AtomicReference<Thread> worker = new AtomicReference<>();
    SqlExecutionResult waitingResult = new SqlExecutionResult();
    try {
      Future<StatusCode> waiting = executor.submit(() -> {
        worker.set(Thread.currentThread());
        entered.countDown();
        return fixture.waiter.execute(sql, waitingResult);
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      awaitParked(worker, waiting);
      assertEquals(StatusCode.OK, fixture.mover.execute("COMMIT", fixture.result));
      assertEquals(expected, waiting.get(5, TimeUnit.SECONDS));
      if (expected == StatusCode.OK) assertEquals(0, waitingResult.affectedRows());
      assertTrue(waitingResult.transactionActive());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, fixture.waiter.execute("ROLLBACK", fixture.result));
  }

  private static void awaitParked(AtomicReference<Thread> worker, Future<?> future) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    Thread.State state = Thread.State.NEW;
    while (!future.isDone() && System.nanoTime() < deadline) {
      Thread thread = worker.get();
      if (thread != null) {
        state = thread.getState();
        if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) break;
      }
      Thread.onSpinWait();
    }
    assertFalse(future.isDone());
    assertTrue(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING);
  }

  private static void createAccount(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "CREATE TABLE accounts (id BIGINT PRIMARY KEY,balance BIGINT)", fixture.result));
    assertEquals(StatusCode.OK, fixture.mover.execute(
        "INSERT INTO accounts VALUES (1,100)", fixture.result));
  }

  private static void beginMoverAndWaiter(Fixture fixture) {
    assertEquals(StatusCode.OK, fixture.mover.execute("BEGIN", fixture.result));
    assertEquals(StatusCode.OK, fixture.waiter.execute(
        "BEGIN REPEATABLE READ", fixture.result));
  }

  private static Fixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessions));
    SqlSession mover = sessions.session();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessions));
    return new Fixture(opened.database(), mover, sessions.session());
  }

  private static final class Fixture {
    final RelationalDatabase database;
    final SqlSession mover;
    final SqlSession waiter;
    final SqlExecutionResult result = new SqlExecutionResult();

    Fixture(RelationalDatabase database, SqlSession mover, SqlSession waiter) {
      this.database = database;
      this.mover = mover;
      this.waiter = waiter;
    }

    void close() {
      assertEquals(StatusCode.OK, mover.close());
      assertEquals(StatusCode.OK, waiter.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
