package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path evidence for one bounded role-indexed JOIN source. */
final class SqlNTableJoinTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e5441424c454a32L, 0x4a4f494e53303031L);

  @Test
  void executesThreeAndEightRoleChainsWithMixedLeftSemantics(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);

    assertIndexedAndTableStagesAgree(session, result);
    assertMixedLeftNullPropagation(session, result);
    assertEightRolesPreserveOwnedText(session, result);
    assertDirectOrderUsesProjectedJoinTuples(session, result);
    assertThreeRolePlanTruth(session, result);
    assertTerminalTemporalFailureClosesEveryRole(session, result);
    assertComposedConsumerBoundaries(session, result);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFixture(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE chain0 (id BIGINT PRIMARY KEY,k BIGINT,label VARCHAR(32))",
            result));
    for (int role = 1; role < 8; role++) {
      assertEquals(
          StatusCode.OK,
          session.execute(
              "CREATE TABLE chain" + role
                  + " (id BIGINT PRIMARY KEY,k BIGINT,v BIGINT NOT NULL)",
              result));
      assertEquals(
          StatusCode.OK,
          session.execute(
              "CREATE INDEX chain" + role + "_k ON chain" + role + "(k)",
              result));
    }
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO chain0 VALUES (1,101,'outer-é😀'),(2,202,'second')",
            result));
    for (int role = 1; role < 8; role++) {
      assertEquals(
          StatusCode.OK,
          session.execute(
              "INSERT INTO chain" + role + " VALUES "
                  + "(1,101," + (role * 10 + 1) + "),"
                  + "(2,202," + (role * 10 + 2) + ")",
              result));
    }
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO chain2 VALUES (3,101,999)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE gap0 (id BIGINT PRIMARY KEY,observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE gap1 (id BIGINT PRIMARY KEY,observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE gap2 (id BIGINT PRIMARY KEY,"
                + "observed TIMESTAMP(6) WITH TIME ZONE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO gap0 VALUES "
                + "(1,TIMESTAMP '2024-03-31 00:30:00'),"
                + "(2,TIMESTAMP '2024-03-31 01:30:00')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO gap1 VALUES "
                + "(1,TIMESTAMP '2024-03-31 00:30:00'),"
                + "(2,TIMESTAMP '2024-03-31 01:30:00')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO gap2 VALUES "
                + "(1,TIMESTAMP WITH TIME ZONE '2024-03-31 00:30:00+00:00')",
            result));
  }

  private static void assertIndexedAndTableStagesAgree(
      SqlSession session, SqlExecutionResult result) {
    assertRows(
        session,
        result,
        "SELECT a.id,b.v,c.v FROM chain0 a "
            + "JOIN chain1 b ON a.k=b.k "
            + "JOIN chain2 c ON b.k=c.k AND c.v<100 WHERE a.id=1",
        new long[][] {{1, 11, 21}});
    assertRows(
        session,
        result,
        "SELECT a.id,b.v,c.v FROM chain0 a "
            + "JOIN chain1 b ON a.k+0=b.k "
            + "JOIN chain2 c ON b.k+0=c.k AND c.v<100 WHERE a.id=1",
        new long[][] {{1, 11, 21}});
  }

  private static void assertMixedLeftNullPropagation(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT a.id,b.v,c.v,d.v FROM chain0 a "
                + "JOIN chain1 b ON a.k=b.k "
                + "LEFT JOIN chain2 c ON b.k=c.k AND c.id=1 "
                + "JOIN chain3 d ON b.k=d.k AND (c.id IS NULL OR c.id=1)",
            cursor));
    assertEquals(SqlTypeDescriptor.BIGINT, session.scanColumnTypeDescriptor(cursor, 2));
    assertTrue(session.scanColumnIsNullable(cursor, 2));
    assertFalse(session.scanColumnIsNullable(cursor, 3));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertFalse(row.isNull(2));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertTrue(row.isNull(2));
    assertEquals(32, row.valueAt(3));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertRows(
        session,
        result,
        "SELECT a.id FROM chain0 a JOIN chain1 b ON a.k=b.k "
            + "LEFT JOIN chain2 c ON b.k=c.k WHERE c.v=123456",
        new long[0][]);
  }

  private static void assertEightRolesPreserveOwnedText(
      SqlSession session, SqlExecutionResult result) {
    String sql = "SELECT a.id+h.v,a.label FROM chain0 a "
        + "JOIN chain1 b ON a.k=b.k JOIN chain2 c ON b.k=c.k AND c.id=1 "
        + "JOIN chain3 d ON c.k=d.k JOIN chain4 e ON d.k=e.k "
        + "JOIN chain5 f ON e.k=f.k JOIN chain6 g ON f.k=g.k "
        + "JOIN chain7 h ON g.k=h.k WHERE a.id=1";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    char[] text = new char[32];
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(72, row.valueAt(0));
    assertEquals(9, row.copyTextAt(1, text, 0));
    assertEquals("outer-é😀", new String(text, 0, 9));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertDirectOrderUsesProjectedJoinTuples(
      SqlSession session, SqlExecutionResult result) {
    String source = " FROM chain0 a JOIN chain1 b ON a.k=b.k "
        + "JOIN chain2 c ON b.k=c.k AND c.v<100";
    assertRows(
        session,
        result,
        "SELECT a.id AS aid,c.v AS cv" + source + " ORDER BY cv DESC",
        new long[][] {{2, 22}, {1, 21}});
    assertRows(
        session,
        result,
        "SELECT a.id AS aid,c.v AS cv" + source
            + " ORDER BY aid DESC LIMIT 1",
        new long[][] {{2, 22}});

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    char[] text = new char[32];
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT a.id AS aid,a.label AS label" + source
                + " ORDER BY label",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(9, row.copyTextAt(1, text, 0));
    assertEquals("outer-é😀", new String(text, 0, 9));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertEquals(6, row.copyTextAt(1, text, 0));
    assertEquals("second", new String(text, 0, 6));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginScan(
            "SELECT a.id,b.id FROM chain0 a JOIN chain1 b ON a.k=b.k "
                + "ORDER BY a.id",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginScan(
            "SELECT a.id,b.id FROM chain0 a JOIN chain1 b ON a.k=b.k "
                + "ORDER BY id",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "SELECT a.id AS aid,b.id AS bid FROM chain0 a "
                + "JOIN chain1 b ON a.k=b.k ORDER BY aid",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT label FROM chain0 WHERE id=1", result));
    assertEquals(9, result.textLengthAt(0));
  }

  private static void assertThreeRolePlanTruth(
      SqlSession session, SqlExecutionResult result) {
    String query = "SELECT a.id AS aid,b.v AS bv,c.v AS cv FROM chain0 a "
        + "JOIN chain1 b ON a.k=b.k LEFT JOIN chain2 c "
        + "ON b.k=c.k AND c.id=1 WHERE a.id>0 ORDER BY aid DESC LIMIT 1";
    assertThreeRolePlan(session, result, "EXPLAIN " + query, false);
    assertThreeRolePlan(session, result, "EXPLAIN ANALYZE " + query, true);
  }

  private static void assertThreeRolePlan(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      boolean analyze) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertPlanRow(session, cursor, row, "table", -1, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "index", 1, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "on", 1, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "join", 1, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "index", 1, analyze ? 3 : -1);
    assertPlanRow(session, cursor, row, "on", 2, analyze ? 1 : -1);
    assertPlanRow(session, cursor, row, "extend", 2, analyze ? 1 : -1);
    assertPlanRow(session, cursor, row, "left", 2, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "filter", 1, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "sort", -1, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "limit", 1, analyze ? 1 : -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertTerminalTemporalFailureClosesEveryRole(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT a.id,b.id,c.id FROM gap0 a "
                + "JOIN gap1 b ON a.id=b.id "
                + "JOIN gap2 c ON b.observed AT TIME ZONE 'Europe/London'=c.observed",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT label FROM chain0 WHERE id=1", result));
    assertEquals(9, result.textLengthAt(0));
  }

  private static void assertComposedConsumerBoundaries(
      SqlSession session, SqlExecutionResult result) {
    String join = "SELECT a.id AS aid,c.v AS cv FROM chain0 a "
        + "JOIN chain1 b ON a.k=b.k "
        + "JOIN chain2 c ON b.k=c.k AND c.v<100";
    SqlScanCursor explain = new SqlScanCursor();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN " + join, explain));
    assertEquals(StatusCode.OK, session.closeScan(explain, result));
    assertRows(
        session,
        result,
        "SELECT aid FROM (" + join + ") joined",
        new long[][] {{1}, {2}});
    assertRows(
        session,
        result,
        "SELECT cv,SUM(aid+1) AS total FROM (" + join
            + ") joined GROUP BY cv HAVING SUM(aid+1)>2 ORDER BY cv",
        new long[][] {{22, 3}});
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute("CREATE VIEW deferred_chain AS " + join, result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE VIEW deferred_derived AS SELECT aid FROM ("
                + join + ") joined",
            result));
    String invalid = "SELECT a.id FROM chain0 a "
        + "JOIN chain1 b ON a.k=b.k "
        + "JOIN chain2 c ON b.v=a.label";
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute("CREATE VIEW invalid_chain AS " + invalid, result));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute(
            "CREATE VIEW invalid_derived AS SELECT id FROM ("
                + invalid + ") invalid_source",
            result));
  }

  private static void assertRows(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      long[][] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long[] values : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      for (int column = 0; column < values.length; column++) {
        assertEquals(values[column], row.valueAt(column));
      }
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertPlanRow(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      String operator,
      long detail,
      long actualRows) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack(operator), row.valueAt(0));
    assertEquals(detail, row.valueAt(1));
    assertEquals(actualRows < 0, row.isNull(2));
    if (actualRows >= 0) assertEquals(actualRows, row.valueAt(2));
  }
}
