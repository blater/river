package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlCompositeForeignKeyTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x464f524549474e4bL, 0x4559544553543031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void compositeForeignKeyUsesReferencedTupleIndexAcrossReopen(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE parents (tenant INTEGER,code VARCHAR(12),amount NUMERIC(10,2),"
            + "PRIMARY KEY(tenant,code,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO parents VALUES (7,'north',12.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE children (id BIGINT,tenant INTEGER,code VARCHAR(12),"
            + "amount NUMERIC(10,2),PRIMARY KEY(id),"
            + "CONSTRAINT fk_parent FOREIGN KEY(tenant,code,amount) "
            + "REFERENCES parents(tenant,code,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO children VALUES (1,7,'north',12.50)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "INSERT INTO children VALUES (2,7,'missing',12.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO children VALUES (3,NULL,'missing',12.50)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "UPDATE children SET amount=99.00 WHERE id=1", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "UPDATE parents SET amount=13.00 WHERE tenant=7", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "DELETE FROM parents WHERE tenant=7 AND code='north' AND amount=12.50", result));
    assertCount(session, 2);
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertCount(session, 2);
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO children VALUES (4,7,'north',12.50)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "INSERT INTO children VALUES (5,8,'north',12.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM children WHERE id=1 OR id=4", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM parents WHERE tenant=7 AND code='north' AND amount=12.50", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void selfReferencingKeyIsBoundToItsDurablePrimaryIndex(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE nodes (id INTEGER,parent_id INTEGER,PRIMARY KEY(id),"
            + "FOREIGN KEY(parent_id) REFERENCES nodes(id))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO nodes VALUES (1,NULL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO nodes VALUES (2,1)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO nodes VALUES (3,3)", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM nodes WHERE id=3", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO nodes VALUES (4,5),(5,NULL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM nodes WHERE id=4", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM nodes WHERE id=5", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO nodes VALUES (10,10)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "UPDATE nodes SET id=11 WHERE id=10", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE nodes SET id=11,parent_id=11 WHERE id=10", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM nodes WHERE id=11", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "INSERT INTO nodes VALUES (3,99)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "DELETE FROM nodes WHERE id=1", result));
    assertEquals(StatusCode.OK, session.execute("DELETE FROM nodes WHERE id=2", result));
    assertEquals(StatusCode.OK, session.execute("DELETE FROM nodes WHERE id=1", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void compositeSelfReferenceCannotRetainTheDisappearingParentKey(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE composite_nodes (tenant INTEGER,id INTEGER,"
            + "parent_tenant INTEGER,parent_id INTEGER,PRIMARY KEY(tenant,id),"
            + "FOREIGN KEY(parent_tenant,parent_id) "
            + "REFERENCES composite_nodes(tenant,id))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO composite_nodes VALUES (1,10,1,10)", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "UPDATE composite_nodes SET id=11 WHERE tenant=1 AND id=10", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE composite_nodes SET id=11,parent_id=11 "
            + "WHERE tenant=1 AND id=10", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM composite_nodes WHERE tenant=1 AND id=11", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void foreignKeyChecksSerializeChildInsertWithParentDelete(@TempDir Path root)
      throws Exception {
    RelationalDatabase database = create(root);
    SqlSession child = session(database);
    SqlSession parent = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, parent.execute(
        "CREATE TABLE p (id INTEGER,PRIMARY KEY(id))", result));
    assertEquals(StatusCode.OK, parent.execute(
        "INSERT INTO p VALUES (1),(2)", result));
    assertEquals(StatusCode.OK, parent.execute(
        "CREATE TABLE c (id INTEGER,parent_id INTEGER,PRIMARY KEY(id),"
            + "FOREIGN KEY(parent_id) REFERENCES p(id))", result));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Thread> worker = new AtomicReference<>();
    try {
      assertEquals(StatusCode.OK, child.execute("BEGIN", result));
      assertEquals(StatusCode.OK, child.execute("INSERT INTO c VALUES (1,1)", result));
      assertEquals(StatusCode.OK, parent.execute("BEGIN", result));
      assertEquals(StatusCode.OK, parent.execute("DELETE FROM p WHERE id=2", result));
      assertEquals(StatusCode.OK, parent.execute("ROLLBACK", result));
      assertEquals(StatusCode.OK, parent.execute("BEGIN", result));
      Future<StatusCode> delete = submit(
          executor, worker, parent, "DELETE FROM p WHERE id=1");
      awaitParked(worker, delete);
      assertFalse(delete.isDone());
      assertEquals(StatusCode.OK, child.execute("COMMIT", result));
      assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, delete.get());
      assertEquals(StatusCode.OK, parent.execute("ROLLBACK", result));
      assertEquals(StatusCode.FOREIGN_KEY_VIOLATION,
          parent.execute("DELETE FROM p WHERE id=1", result));

      assertEquals(StatusCode.OK, parent.execute("BEGIN", result));
      assertEquals(StatusCode.OK, parent.execute("DELETE FROM p WHERE id=2", result));
      assertEquals(StatusCode.OK, child.execute("BEGIN", result));
      assertEquals(StatusCode.OK,
          child.execute("INSERT INTO c VALUES (2,1)", result));
      assertEquals(StatusCode.OK, child.execute("ROLLBACK", result));
      assertEquals(StatusCode.OK, child.execute("BEGIN", result));
      Future<StatusCode> insert = submit(
          executor, worker, child, "INSERT INTO c VALUES (3,2)");
      awaitParked(worker, insert);
      assertFalse(insert.isDone());
      assertEquals(StatusCode.OK, parent.execute("COMMIT", result));
      assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, insert.get());
      assertEquals(StatusCode.OK, child.execute("ROLLBACK", result));
      assertEquals(StatusCode.FOREIGN_KEY_VIOLATION,
          child.execute("INSERT INTO c VALUES (3,2)", result));
    } finally {
      executor.shutdownNow();
    }
    assertEquals(StatusCode.OK, child.close());
    assertEquals(StatusCode.OK, parent.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static Future<StatusCode> submit(
      ExecutorService executor, AtomicReference<Thread> worker,
      SqlSession session, String sql) {
    worker.set(null);
    return executor.submit(() -> {
      worker.set(Thread.currentThread());
      return session.execute(sql, new SqlExecutionResult());
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

  @Test
  void savepointRollbackRemovesCompositeForeignKeyIntent(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE parents (tenant INTEGER,amount NUMERIC(10,2),"
            + "PRIMARY KEY(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE children (id BIGINT,tenant INTEGER,amount NUMERIC(10,2),"
            + "PRIMARY KEY(id),FOREIGN KEY(tenant,amount) "
            + "REFERENCES parents(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO parents VALUES (7,12.50),(7,12.75)", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SAVEPOINT before_child", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO children VALUES (1,7,12.50)", result));
    assertEquals(StatusCode.OK,
        session.execute("ROLLBACK TO SAVEPOINT before_child", result));
    assertEquals(StatusCode.OK, session.execute(
        "DELETE FROM parents WHERE tenant=7 AND amount=12.50", result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO children VALUES (2,7,12.75)", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void walOnlyReopenPreservesCompositeForeignKeyRows(@TempDir Path root) {
    RelationalDatabase database = create(root);
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE parents (tenant INTEGER,amount NUMERIC(10,2),"
            + "PRIMARY KEY(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE children (id BIGINT,tenant INTEGER,amount NUMERIC(10,2),"
            + "PRIMARY KEY(id),FOREIGN KEY(tenant,amount) "
            + "REFERENCES parents(tenant,amount))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO parents VALUES (7,12.50),(7,12.75)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO children VALUES (1,7,12.50)", result));
    assertEquals(StatusCode.OK, session.execute(
        "UPDATE children SET amount=12.75 WHERE id=1", result));
    assertEquals(StatusCode.FOREIGN_KEY_VIOLATION, session.execute(
        "UPDATE children SET amount=13.00 WHERE id=1", result));
    // Deliberately omit CHECKPOINT: the committed schema and row must be WAL-recoverable.
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    database = open(root);
    session = session(database);
    assertEquals(StatusCode.OK, session.execute(
        "SELECT COUNT(*) FROM children WHERE id=1 AND tenant=7 AND amount=12.75", result));
    assertEquals(1, result.value());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertCount(SqlSession session, long expected) {
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute("SELECT COUNT(*) FROM children", result));
    assertEquals(expected, result.value());
  }

  private static RelationalDatabase create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static RelationalDatabase open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    return opened.database();
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
