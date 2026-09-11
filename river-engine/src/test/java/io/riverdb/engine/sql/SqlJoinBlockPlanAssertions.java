package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;

/** Assertions for JOIN block EXPLAIN shape and atomic temporal failures. */
final class SqlJoinBlockPlanAssertions {
  private static final PlanRow[] EDGE_ANALYZED = {
    new PlanRow("block", 1, 2),
    new PlanRow("block", 2, 2),
    new PlanRow("index", 1, 2),
    new PlanRow("index", 1, 3),
    new PlanRow("on", 2, 2),
    new PlanRow("join", 2, 2),
    new PlanRow("filter", 1, 2)
  };
  private static final PlanRow[] RESIDUAL_ANALYZED = {
    new PlanRow("block", 1, 2),
    new PlanRow("block", 2, 2),
    new PlanRow("table", -1, 4),
    new PlanRow("table", -1, 16),
    new PlanRow("on", 1, 2),
    new PlanRow("join", 1, 2)
  };
  private static final PlanRow[] EDGE_ESTIMATED = {
    new PlanRow("block", 1, -1),
    new PlanRow("block", 2, -1),
    new PlanRow("index", 1, -1),
    new PlanRow("index", 1, -1),
    new PlanRow("on", 2, -1),
    new PlanRow("join", 2, -1),
    new PlanRow("filter", 1, -1)
  };

  private SqlJoinBlockPlanAssertions() {}

  static void assertJoinPlans(SqlSession session, SqlExecutionResult result) {
    assertPlan(
        session,
        result,
        "EXPLAIN ANALYZE SELECT lid FROM (SELECT l.id AS lid "
            + "FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE WHERE l.bucket=10) joined",
        EDGE_ANALYZED);
    assertPlan(
        session,
        result,
        "EXPLAIN ANALYZE SELECT lid FROM (SELECT l.id AS lid "
            + "FROM left_rows l JOIN right_rows r ON l.id+10=r.id) joined",
        RESIDUAL_ANALYZED);
    assertPlan(
        session,
        result,
        "EXPLAIN SELECT lid FROM (SELECT l.id AS lid "
            + "FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE WHERE l.bucket=10) joined",
        EDGE_ESTIMATED);
  }

  static void assertTemporalAtomicFailure(SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginScan(
            "SELECT shifted FROM (SELECT l.observed AT TIME ZONE 'No/Such' "
                + "AS shifted FROM left_rows l JOIN right_rows r "
                + "ON l.id=r.left_id WHERE l.id<0) joined",
            cursor));
    assertFalse(cursor.isActive());
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT shifted FROM (SELECT l.observed AT TIME ZONE "
                + "'Europe/London' AS shifted FROM left_rows l "
                + "JOIN right_rows r ON l.id=r.left_id AND r.flag=TRUE) joined",
            cursor));
    SqlScanRowResult plan = new SqlScanRowResult();
    assertPlanRow(session, cursor, plan, "block", 1, -1);
    assertPlanRow(session, cursor, plan, "block", 2, -1);
    assertPlanRow(session, cursor, plan, "table", -1, -1);
    assertPlanRow(session, cursor, plan, "index", 1, -1);
    assertPlanRow(session, cursor, plan, "on", 2, -1);
    assertPlanRow(session, cursor, plan, "join", 2, -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, plan));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT shifted FROM (SELECT l.observed AT TIME ZONE 'Europe/London' "
                + "AS shifted FROM left_rows l JOIN right_rows r "
                + "ON l.id=r.left_id AND r.flag=TRUE WHERE l.id=1) joined",
            cursor));
    SqlScanRowResult valid = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.nextScan(cursor, valid));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, valid));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginScan(
            "SELECT shifted FROM (SELECT l.observed AT TIME ZONE 'Europe/London' "
                + "AS shifted FROM left_rows l JOIN right_rows r "
                + "ON l.id=r.left_id AND r.flag=TRUE) joined",
            cursor));
    assertFalse(cursor.isActive());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM left_rows WHERE id=2", result));
    assertEquals(10, result.valueAt(0));
  }

  private static void assertPlan(
      SqlSession session, SqlExecutionResult result, String sql, PlanRow[] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (PlanRow plan : expected) {
      assertPlanRow(session, cursor, row, plan.operator(), plan.detail(), plan.rows());
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
      long rows) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack(operator), row.valueAt(0));
    assertEquals(detail, row.valueAt(1));
    assertEquals(rows < 0, row.isNull(2));
    if (rows >= 0) assertEquals(rows, row.valueAt(2));
  }

  private record PlanRow(String operator, long detail, long rows) {}
}
