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

/** Ordered duplicate-run, LEFT residual, P3, plan, and spill evidence for merge JOIN. */
final class SqlMergeJoinTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4d455247454a4f49L, 0x4e53545241544547L);

  @Test
  void mergesOrderedEqualityThroughTheSharedChain(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);

    assertDuplicateCartesianAndP3(session, result);
    assertTypedComparatorAndNestedIdentity(session, result);
    assertLeftResidualAndPlans(session, result);
    assertOversizedRunSpillsAndReuses(session, result);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFixture(SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE merge_left (id BIGINT PRIMARY KEY,k BIGINT NOT NULL,flag BOOLEAN)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE merge_right (id BIGINT PRIMARY KEY,k BIGINT,flag BOOLEAN)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO merge_left VALUES "
                + "(1,10,TRUE),(2,10,TRUE),(3,20,TRUE),(4,30,TRUE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO merge_right VALUES "
                + "(11,10,TRUE),(12,10,FALSE),(13,20,TRUE),(14,40,TRUE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX merge_left_k ON merge_left(k)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX merge_right_k ON merge_right(k)", result));
  }

  private static void assertDuplicateCartesianAndP3(
      SqlSession session, SqlExecutionResult result) {
    String source = "SELECT a.id AS aid,b.id AS bid FROM merge_left a "
        + "JOIN merge_right b ON a.k=b.k";
    assertPlanContains(session, result, "EXPLAIN " + source, "merge");
    assertRows(
        session,
        result,
        "SELECT aid,SUM(bid) AS total FROM (" + source
            + ") pairs GROUP BY aid ORDER BY aid",
        new long[][] {{1, 23}, {2, 23}, {3, 13}});
    assertP3MergePlan(
        session,
        result,
        "EXPLAIN ANALYZE SELECT aid,SUM(bid) AS total FROM (" + source
            + ") pairs GROUP BY aid ORDER BY aid");
  }

  private static void assertTypedComparatorAndNestedIdentity(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE merge_exact_left "
                + "(id BIGINT PRIMARY KEY,amount DECIMAL(8,2) NOT NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE merge_exact_right "
                + "(id BIGINT PRIMARY KEY,amount DECIMAL(8,2) NOT NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO merge_exact_left VALUES (1,1.20),(2,2.00)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO merge_exact_right VALUES (11,1.20),(12,1.20),(13,2.00)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX merge_exact_left_amount ON merge_exact_left(amount)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX merge_exact_right_amount ON merge_exact_right(amount)",
            result));
    String exact = "SELECT a.id,b.id FROM merge_exact_left a "
        + "JOIN merge_exact_right b ON a.amount=b.amount";
    assertPlanContains(session, result, "EXPLAIN " + exact, "merge");
    assertCount(session, result, "SELECT COUNT(*) FROM (" + exact + ") pairs", 3);

    String nested = "SELECT a.id,b.id FROM merge_left a "
        + "JOIN merge_right b ON a.k+0=b.k";
    assertPlanContains(session, result, "EXPLAIN " + nested, "join");
    assertCount(session, result, "SELECT COUNT(*) FROM (" + nested + ") pairs", 5);
  }

  private static void assertLeftResidualAndPlans(
      SqlSession session, SqlExecutionResult result) {
    String query = "SELECT a.id AS aid,b.id AS bid FROM merge_left a "
        + "LEFT JOIN merge_right b "
        + "ON a.k=b.k AND b.flag=TRUE";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(query + " ORDER BY aid", cursor));
    assertValueRow(session, cursor, row, 1, 11);
    assertValueRow(session, cursor, row, 2, 11);
    assertValueRow(session, cursor, row, 3, 13);
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(4, row.valueAt(0));
    assertTrue(row.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN ANALYZE " + query, cursor));
    assertPlanRow(session, cursor, row, "index", 1, 4);
    assertPlanRow(session, cursor, row, "index", 1, 5);
    assertPlanRow(session, cursor, row, "on", 2, 3);
    assertPlanRow(session, cursor, row, "extend", 1, 1);
    assertPlanRow(session, cursor, row, "mleft", 2, 4);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertOversizedRunSpillsAndReuses(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE merge_probe (id BIGINT PRIMARY KEY,k BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE merge_run (id BIGINT PRIMARY KEY,k BIGINT)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO merge_probe VALUES (1,7)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX merge_probe_k ON merge_probe(k)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX merge_run_k ON merge_run(k)", result));
    for (int start = 1; start <= 1_025; start += 64) {
      int end = Math.min(1_025, start + 63);
      StringBuilder insert = new StringBuilder("INSERT INTO merge_run VALUES ");
      for (int id = start; id <= end; id++) {
        if (id > start) insert.append(',');
        insert.append('(').append(id).append(",7)");
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    }
    assertCount(
        session,
        result,
        "SELECT COUNT(*) FROM (SELECT a.id AS aid,b.id AS bid "
            + "FROM merge_probe a JOIN merge_run b ON a.k=b.k) pairs",
        1_025);
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT k FROM merge_probe WHERE id=1", result));
    assertEquals(7, result.valueAt(0));
  }

  private static void assertValueRow(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long left,
      long right) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(left, row.valueAt(0));
    assertEquals(right, row.valueAt(1));
  }

  private static void assertCount(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      long expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(expected, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
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
    assertEquals(actualRows, row.valueAt(2));
  }

  private static void assertPlanContains(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      String operator) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    boolean found = false;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      found |= row.valueAt(0) == PackedText.pack(operator);
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertTrue(found);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertP3MergePlan(
      SqlSession session, SqlExecutionResult result, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    int orderedInputs = 0;
    boolean on = false;
    boolean merge = false;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      long operator = row.valueAt(0);
      if (operator == PackedText.pack("index")) orderedInputs++;
      if (operator == PackedText.pack("on")) on = true;
      if (operator == PackedText.pack("merge")) merge = true;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(2, orderedInputs);
    assertTrue(on);
    assertTrue(merge);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
