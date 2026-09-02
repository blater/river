package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverForeignKeyTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x464f524549474e4bL, 0x4559434f4e535431L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void enforcesReferencesAcrossMutationsTransactionsAndRestart(@TempDir Path root)
      throws Exception {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE parents "
                + "(id BIGINT PRIMARY KEY, external_id BIGINT UNIQUE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE wrong_target "
                + "(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES parents(external_id))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE children "
                + "(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES parents(id), "
                + "exclusive_parent_id BIGINT UNIQUE REFERENCES parents(id))",
            result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute("DROP INDEX _river_reference_3_1 ON children", result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DROP TABLE parents", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO parents VALUES (1, 100), (2, 200), (3, 300)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO wrong_target VALUES (1, 100)", result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("INSERT INTO wrong_target VALUES (2, 999)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM wrong_target WHERE id=1", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE wrong_target", result));

    SessionOpenResult secondResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(secondResult));
    RiverSession second = secondResult.session();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Thread> worker = new AtomicReference<>();
    try {
      assertEquals(StatusCode.OK, session.execute("BEGIN REPEATABLE READ", result));
      assertEquals(
          StatusCode.OK,
          session.execute("INSERT INTO children VALUES (9, 1, NULL)", result));
      Future<StatusCode> delete = submit(executor, worker, second,
          "DELETE FROM parents WHERE id=1");
      awaitParked(worker, delete);
      assertFalse(delete.isDone());
      assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
      assertEquals(StatusCode.OK, delete.get());
      assertEquals(StatusCode.OK,
          session.execute("INSERT INTO parents VALUES (1, 100)", result));

      assertEquals(StatusCode.OK, second.execute("BEGIN REPEATABLE READ", result));
      assertEquals(
          StatusCode.OK,
          second.execute("DELETE FROM parents WHERE id=2", result));
      Future<StatusCode> insert = submit(executor, worker, session,
          "INSERT INTO children VALUES (9, 2, NULL)");
      awaitParked(worker, insert);
      assertFalse(insert.isDone());
      assertEquals(StatusCode.OK, second.execute("ROLLBACK", result));
      assertEquals(StatusCode.OK, insert.get());
      assertEquals(StatusCode.OK,
          session.execute("DELETE FROM children WHERE id=9", result));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, second.close());

    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO children VALUES "
                + "(10, 1, NULL), (11, 2, NULL), (12, NULL, NULL), (15, NULL, 3)",
            result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("INSERT INTO children VALUES (13, 99, NULL)", result));
    assertMissing(13, session, result);
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute(
            "INSERT INTO children VALUES (13, 1, NULL), (14, 99, NULL)", result));
    assertMissing(13, session, result);
    assertMissing(14, session, result);

    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute("INSERT INTO children VALUES (16, NULL, 3)", result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DELETE FROM parents WHERE id=3", result));
    assertEquals(StatusCode.OK, session.execute("DELETE FROM children WHERE id=15", result));
    assertEquals(StatusCode.OK, session.execute("DELETE FROM parents WHERE id=3", result));

    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("UPDATE children SET parent_id=99 WHERE id=10", result));
    assertValue(1, "SELECT parent_id FROM children WHERE id=10", session, result);
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DELETE FROM parents WHERE id=1", result));
    assertValue(100, "SELECT external_id FROM parents WHERE id=1", session, result);
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DROP TABLE parents", result));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE transient_children "
                + "(id BIGINT PRIMARY KEY, parent_id BIGINT REFERENCES parents(id))",
            result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO transient_children VALUES (1, 1)", result));

    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE parents RENAME TO guardians", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO children VALUES (13, 2, NULL)", result));
    assertEquals(2, countRows(session, "SELECT id FROM children WHERE parent_id=2"));
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM children WHERE id=10", result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DELETE FROM guardians WHERE id >= 1 AND id < 3", result));
    assertValue(100, "SELECT external_id FROM guardians WHERE id=1", session, result);
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM guardians WHERE id=1", result));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("INSERT INTO children VALUES (14, 99, NULL)", result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DELETE FROM guardians WHERE id=2", result));
    assertEquals(
        StatusCode.FOREIGN_KEY_VIOLATION,
        session.execute("DROP TABLE guardians", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE children", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE guardians", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static Future<StatusCode> submit(
      ExecutorService executor, AtomicReference<Thread> worker,
      RiverSession session, String sql) {
    worker.set(null);
    return executor.submit(() -> {
      worker.set(Thread.currentThread());
      return session.execute(sql, new CommandResult());
    });
  }

  private static void awaitParked(
      AtomicReference<Thread> worker, Future<StatusCode> operation) {
    long deadline = System.nanoTime() + 1_000_000_000L;
    while (!operation.isDone() && System.nanoTime() < deadline) {
      Thread thread = worker.get();
      if (thread != null && (thread.getState() == Thread.State.WAITING
          || thread.getState() == Thread.State.TIMED_WAITING)) return;
      Thread.onSpinWait();
    }
  }

  private static void assertMissing(
      long key,
      RiverSession session,
      CommandResult result) {
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id FROM children WHERE id=" + key, result));
  }

  private static void assertValue(
      long expected,
      String sql,
      RiverSession session,
      CommandResult result) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(true, result.rowAvailable());
    assertEquals(expected, result.valueAt(0));
  }

  private static int countRows(RiverSession session, String sql) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    int count = 0;
    StatusCode status = query.next(row);
    while (status.isOk() && row.isAvailable()) {
      count++;
      status = query.next(row);
    }
    assertEquals(StatusCode.OK, status);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
    return count;
  }
}
