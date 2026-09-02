package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public-session evidence for nested UNION execution over real bound leaf plans. */
final class SqlUnionSessionExecutionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x554e494f4e534554L, 0x455845435554494fL);

  @Test
  void executesNestedSetBoundariesWithJoinIndexAndLocalTails(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK, RelationalDatabase.create(
        root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    createFixture(session, execution);

    String query = "(SELECT a.tenant,a.account FROM accounts a JOIN entries e "
        + "ON a.tenant=e.tenant AND a.account=e.account "
        + "WHERE (e.delta=5 OR e.delta=9) ORDER BY account DESC LIMIT 2 "
        + "UNION SELECT tenant,account FROM accounts WHERE tenant=1) "
        + "UNION ALL (SELECT tenant,account FROM entries WHERE tenant=2 "
        + "ORDER BY account DESC LIMIT 1) "
        + "ORDER BY tenant DESC,account DESC LIMIT 5";
    assertRows(session, execution, query, new long[][] {
        {2, 1}, {2, 1}, {1, 2}, {1, 1}
    });

    assertRows(
        session,
        execution,
        "SELECT tenant,account FROM accounts WHERE tenant=1 UNION "
            + "SELECT tenant,account FROM entries WHERE tenant=1 "
            + "ORDER BY account",
        new long[][] {{1, 1}, {1, 2}});
    assertRows(
        session,
        execution,
        "SELECT tenant FROM accounts a WHERE EXISTS "
            + "(SELECT id FROM entries e WHERE e.tenant=a.tenant AND e.delta=5) "
            + "UNION ALL SELECT tenant FROM accounts a WHERE EXISTS "
            + "(SELECT id FROM entries e WHERE e.tenant=a.tenant AND e.delta=9) "
            + "ORDER BY tenant",
        new long[][] {{1}, {1}, {2}});
    assertRows(
        session,
        execution,
        "SELECT tenant,COUNT(*) AS n FROM entries GROUP BY tenant "
            + "HAVING COUNT(*)>=2 UNION ALL "
            + "SELECT tenant,COUNT(*) AS n FROM entries WHERE tenant=1 "
            + "GROUP BY tenant HAVING COUNT(*)>=2 ORDER BY tenant DESC",
        new long[][] {{2, 2}, {1, 2}, {1, 2}});
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFixture(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE accounts (tenant INTEGER,account INTEGER,balance BIGINT,"
            + "PRIMARY KEY(tenant,account))",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE entries (id BIGINT PRIMARY KEY,tenant INTEGER,"
            + "account INTEGER,delta BIGINT)",
        result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE INDEX entries_owner ON entries(tenant,account)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO accounts VALUES (1,1,100),(1,2,200),(2,1,300)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO entries VALUES "
            + "(10,1,1,5),(11,1,2,7),(12,2,1,9),(13,2,1,11)",
        result));
  }

  private static void assertRows(
      SqlSession session,
      SqlExecutionResult execution,
      String sql,
      long[][] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    assertEquals("tenant", session.scanColumnName(cursor, 0).toString());
    assertEquals(expected[0].length, cursor.projectedColumnCount());
    for (long[] values : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
      for (int column = 0; column < values.length; column++) {
        assertEquals(values[column], row.valueAt(column));
      }
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution), sql);
  }
}
