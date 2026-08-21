package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Typed, residual, plan, and bounded fallback evidence for the shared hash stage. */
final class SqlHashJoinTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x484153484a4f494eL, 0x5354524154454759L);

  @Test
  void hashesTypedEqualityAndFallsBackThroughTheExistingStore(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    createTypedFixture(session, result);

    assertStableResidualAndLeftSemantics(session, result);
    assertTypedFamilies(session, result);
    assertHashPlans(session, result);
    assertSpilledBuildFallsBackAndReuses(session, result);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createTypedFixture(SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE hash_left (id BIGINT PRIMARY KEY,k BIGINT,"
                + "amount DECIMAL(8,2),flag BOOLEAN,observed TIMESTAMP(6),"
                + "label VARCHAR(32))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE hash_right (id BIGINT PRIMARY KEY,k BIGINT,"
                + "amount DECIMAL(9,3),flag BOOLEAN,observed TIMESTAMP(3),"
                + "label VARCHAR(32))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO hash_left VALUES "
                + "(1,10,1.20,TRUE,TIMESTAMP '2024-01-01 12:00:00.123000','é😀'),"
                + "(2,20,2.00,FALSE,TIMESTAMP '2024-01-02 12:00:00.456000','猫'),"
                + "(3,NULL,NULL,NULL,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO hash_right VALUES "
                + "(11,10,1.200,TRUE,TIMESTAMP '2024-01-01 12:00:00.123','é😀'),"
                + "(12,10,1.200,TRUE,TIMESTAMP '2024-01-01 12:00:00.123','é😀'),"
                + "(13,20,2.000,FALSE,TIMESTAMP '2024-01-02 12:00:00.456','猫'),"
                + "(14,NULL,NULL,NULL,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE hash_third (id BIGINT PRIMARY KEY,k BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO hash_third VALUES (101,10),(102,20)", result));
  }

  private static void assertStableResidualAndLeftSemantics(
      SqlSession session, SqlExecutionResult result) {
    assertRows(
        session,
        result,
        "SELECT a.id,b.id FROM hash_left a JOIN hash_right b "
            + "ON a.k=b.k AND b.id>11",
        new long[][] {{1, 12}, {2, 13}});
    assertRows(
        session,
        result,
        "SELECT a.id,b.id FROM hash_left a JOIN hash_right b "
            + "ON a.k+0=b.k AND b.id>11",
        new long[][] {{1, 12}, {2, 13}});
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT a.id,b.id FROM hash_left a LEFT JOIN hash_right b "
                + "ON a.k=b.k AND b.id=999",
            cursor));
    for (int id = 1; id <= 3; id++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(id, row.valueAt(0));
      assertTrue(row.isNull(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertTypedFamilies(SqlSession session, SqlExecutionResult result) {
    assertPairIds(session, result, "a.amount=b.amount", 3);
    assertPairIds(session, result, "a.flag=b.flag", 3);
    assertPairIds(session, result, "a.observed=b.observed", 3);
    assertPairIds(session, result, "a.label=b.label", 3);
    String later = "SELECT a.id AS aid,c.id AS cid FROM hash_left a "
        + "JOIN hash_right b ON a.k+0=b.k JOIN hash_third c ON b.k=c.k";
    assertRows(
        session,
        result,
        "SELECT cid,SUM(aid) AS total FROM (" + later
            + ") joined GROUP BY cid ORDER BY cid",
        new long[][] {{101, 2}, {102, 2}});
    assertPlanContains(session, result, "EXPLAIN ANALYZE " + later, "hash");
  }

  private static void assertPairIds(
      SqlSession session, SqlExecutionResult result, String edge, int expectedRows) {
    String query = "SELECT a.id,b.id FROM hash_left a JOIN hash_right b ON " + edge;
    assertPlanContains(session, result, "EXPLAIN " + query, "hash");
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            query, cursor));
    int rows = 0;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) rows++;
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expectedRows, rows);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertHashPlans(SqlSession session, SqlExecutionResult result) {
    String query = "SELECT a.id,b.id FROM hash_left a JOIN hash_right b "
        + "ON a.k=b.k AND b.id>11";
    assertPlan(session, result, "EXPLAIN " + query, false, false);
    assertPlan(session, result, "EXPLAIN ANALYZE " + query, true, false);
    assertPlan(
        session,
        result,
        "EXPLAIN ANALYZE " + query + " LIMIT 0",
        true,
        true);
  }

  private static void assertPlan(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      boolean analyzed,
      boolean zero) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertPlanRow(session, cursor, row, "table", -1, analyzed ? zero ? 0 : 3 : -1);
    assertPlanRow(session, cursor, row, "table", 1, analyzed ? zero ? 0 : 3 : -1);
    assertPlanRow(session, cursor, row, "on", 2, analyzed ? zero ? 0 : 2 : -1);
    assertPlanRow(session, cursor, row, "hash", 2, analyzed ? zero ? 0 : 2 : -1);
    if (zero) assertPlanRow(session, cursor, row, "limit", 0, 0);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertSpilledBuildFallsBackAndReuses(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_probe (id BIGINT PRIMARY KEY,k BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_build (id BIGINT PRIMARY KEY,k BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO spill_probe VALUES (1,1025),(2,1),(3,2000)", result));
    for (int start = 1; start <= 1_025; start += 64) {
      int end = Math.min(1_025, start + 63);
      StringBuilder insert = new StringBuilder("INSERT INTO spill_build VALUES ");
      for (int id = start; id <= end; id++) {
        if (id > start) insert.append(',');
        insert.append('(').append(id).append(',').append(id).append(')');
      }
      assertEquals(
          StatusCode.OK, session.execute(insert.toString(), result), "insert start " + start);
    }
    String query = "SELECT a.id,b.id FROM spill_probe a JOIN spill_build b ON a.k=b.k";
    assertRows(session, result, query, new long[][] {{1, 1025}, {2, 1}});
    SqlScanCursor leftCursor = new SqlScanCursor();
    SqlScanRowResult leftRow = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT a.id,b.id FROM spill_probe a LEFT JOIN spill_build b ON a.k=b.k",
            leftCursor));
    assertEquals(StatusCode.OK, session.nextScan(leftCursor, leftRow));
    assertEquals(1, leftRow.valueAt(0));
    assertEquals(StatusCode.OK, session.nextScan(leftCursor, leftRow));
    assertEquals(2, leftRow.valueAt(0));
    assertEquals(StatusCode.OK, session.nextScan(leftCursor, leftRow));
    assertEquals(3, leftRow.valueAt(0));
    assertTrue(leftRow.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(leftCursor, leftRow));
    assertEquals(StatusCode.OK, session.closeScan(leftCursor, result));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN ANALYZE " + query, cursor));
    assertPlanRow(session, cursor, row, "table", -1, 3);
    assertPlanRow(session, cursor, row, "table", 1, 3_075);
    assertPlanRow(session, cursor, row, "on", 1, 2);
    assertPlanRow(session, cursor, row, "fallbk", 1, 2);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertPlanContains(
        session,
        result,
        "EXPLAIN ANALYZE SELECT aid FROM (SELECT a.id AS aid,b.id AS bid "
            + "FROM spill_probe a JOIN spill_build b ON a.k=b.k) joined",
        "fallbk");
    assertEquals(StatusCode.OK, session.execute("SELECT k FROM spill_probe WHERE id=1", result));
    assertEquals(1025, result.valueAt(0));
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

  private static void assertPlanContains(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      String expectedOperator) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    boolean found = false;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      found |= row.valueAt(0) == PackedText.pack(expectedOperator);
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertTrue(found);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
