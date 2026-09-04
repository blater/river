package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorSetExecutionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4453435345544558L, 0x4543555445303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void groupsDeduplicatesAndAppliesHaving(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE events "
            + "(id BIGINT PRIMARY KEY,category BIGINT,amount BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX events_composite ON events(category,amount)", result));
    assertEquals(StatusCode.OK, session.execute(
        "DROP INDEX events_composite ON events", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX events_unique_composite ON events(category,amount)", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(StatusCode.CONFLICT, session.execute(
        "DROP INDEX events_unique_composite ON events", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO events VALUES (1,10,100),(2,10,200),(3,20,300),"
            + "(4,10,400),(5,30,500)", result));

    assertRows(session,
        "SELECT category,COUNT(*) FROM events WHERE category>=10 AND category<30 "
            + "AND amount>=150 AND amount<450 GROUP BY category ORDER BY category",
        new long[] {10, 20}, new long[] {2, 1});
    assertRows(session,
        "SELECT category,COUNT(*) FROM events GROUP BY category "
            + "HAVING COUNT(*)>=2 ORDER BY category",
        new long[] {10}, new long[] {3});
    assertRows(session,
        "SELECT DISTINCT category FROM events WHERE amount>=150 AND amount<450 "
            + "ORDER BY category LIMIT 2",
        new long[] {10, 20}, null);
    assertRows(session, "SELECT DISTINCT amount FROM events",
        new long[] {100, 200, 300, 400, 500}, null);

    assertEquals(StatusCode.OK, session.execute(
        "CREATE UNIQUE INDEX events_unique_composite ON events(category,amount)", result));
    assertEquals(StatusCode.UNIQUE_VIOLATION, session.execute(
        "INSERT INTO events VALUES (6,10,100)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO events VALUES (6,40,600)", result));

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void reusesSixtyFivePhysicalKeyDescriptorsAcrossStatements(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(wideIndexedTable(), result));
    for (int index = 0; index < 64; index++) {
      assertEquals(StatusCode.OK, session.execute(
          "CREATE INDEX wide_i" + index + " ON wide_keys(c" + index + ")", result));
    }
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, session.execute(
        "CREATE INDEX wide_i64 ON wide_keys(c0)", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute(wideInsert(1), result));
    assertEquals(StatusCode.OK, session.execute(wideInsert(2), result));
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static String wideIndexedTable() {
    StringBuilder sql = new StringBuilder(
        "CREATE TABLE wide_keys (id BIGINT PRIMARY KEY");
    for (int index = 0; index < 64; index++) {
      sql.append(",c").append(index).append(" BIGINT");
    }
    return sql.append(')').toString();
  }

  private static String wideInsert(long id) {
    StringBuilder sql = new StringBuilder("INSERT INTO wide_keys VALUES (");
    sql.append(id);
    for (int index = 0; index < 64; index++) sql.append(',').append(id * 100 + index);
    return sql.append(')').toString();
  }

  private static void assertRows(
      SqlSession session, String sql, long[] first, long[] second) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (int index = 0; index < first.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(0, row.key());
      assertEquals(first[index], row.valueAt(0));
      if (second != null) assertEquals(second[index], row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
