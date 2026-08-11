package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.ViewDefinition;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlViewTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x44555241424c4556L, 0x49455753454d414eL);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void persistsTransactionalViewsAndExecutesOuterPredicates(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1,7,50),(2,7,150),(3,8,200),(4,7,300)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW valuable AS "
                + "SELECT id, category AS kind, amount FROM events "
                + "WHERE amount>=100",
            execution));
    RelationalSessionOpenResult catalogSession = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(catalogSession));
    assertEquals(
        StatusCode.OK,
        catalogSession.session().begin(IsolationLevel.REPEATABLE_READ));
    assertEquals(
        StatusCode.OK,
        catalogSession.session().resolveView(
            "valuable", new ViewDefinition()));
    assertEquals(
        StatusCode.OK,
        catalogSession.session().abort(new TransactionOutcome()));
    assertRows(
        session,
        execution,
        "SELECT id, amount FROM valuable WHERE kind=7 ORDER BY id",
        new long[][] {{2, 150}, {4, 300}});
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET amount=350 WHERE id=1", execution));
    assertRows(
        session,
        execution,
        "SELECT id, amount FROM valuable WHERE kind=7 ORDER BY id",
        new long[][] {{1, 350}, {2, 150}, {4, 300}});
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "CREATE VIEW events AS SELECT id, amount FROM events",
            execution));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "CREATE VIEW invalid_view AS SELECT missing FROM events",
            execution));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("DROP TABLE events", execution));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("ALTER TABLE events RENAME TO renamed_events", execution));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "ALTER TABLE events RENAME COLUMN amount TO total", execution));

    assertEquals(StatusCode.OK, session.execute("BEGIN", execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW temporary AS SELECT id, amount FROM events",
            execution));
    assertRows(
        session,
        execution,
        "SELECT id FROM temporary WHERE amount>=200 ORDER BY id",
        new long[][] {{1}, {3}, {4}});
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", execution));
    SqlScanCursor missing = new SqlScanCursor();
    assertEquals(
        StatusCode.CONFLICT,
        session.beginScan("SELECT id FROM temporary", missing));

    SqlSession observer = openSession(database);
    assertEquals(StatusCode.OK, session.execute("BEGIN", execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW published AS SELECT id, amount FROM events",
            execution));
    SqlScanCursor unpublished = new SqlScanCursor();
    assertEquals(
        StatusCode.RETRY,
        observer.beginScan("SELECT id FROM published", unpublished));
    assertEquals(StatusCode.OK, session.execute("COMMIT", execution));
    assertRows(
        observer,
        execution,
        "SELECT id FROM published WHERE amount>=300 ORDER BY id",
        new long[][] {{1}, {4}});
    assertEquals(
        StatusCode.OK,
        observer.execute("DROP VIEW published", execution));
    assertEquals(StatusCode.OK, observer.close());

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    opened.reset();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    session = openSession(database);
    assertRows(
        session,
        execution,
        "SELECT id, kind FROM valuable WHERE amount>=150 ORDER BY id",
        new long[][] {{1, 7}, {2, 7}, {3, 8}, {4, 7}});
    assertEquals(
        StatusCode.OK,
        session.execute("DROP VIEW valuable", execution));
    assertEquals(
        StatusCode.CONFLICT,
        session.beginScan("SELECT id FROM valuable", missing));
    assertEquals(
        StatusCode.OK,
        session.execute("ALTER TABLE events RENAME TO renamed_events", execution));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static SqlSession openSession(RelationalDatabase database) {
    SqlSessionOpenResult result = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, result));
    return result.session();
  }

  private static void assertRows(
      SqlSession session,
      SqlExecutionResult execution,
      String sql,
      long[][] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long[] values : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(values.length, row.columnCount());
      for (int column = 0; column < values.length; column++) {
        assertEquals(values[column], row.valueAt(column));
      }
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }
}
