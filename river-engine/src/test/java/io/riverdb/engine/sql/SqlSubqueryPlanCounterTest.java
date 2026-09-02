package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSubqueryPlanCounterTest {
  private static final long CORRELATED = 1L << 18;

  @Test
  void reportsIdenticalSixRowShapeAndTruthfulCacheCounters(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("CREATE INDEX inner_region ON inner_rows(region)");
    String uncorrelated = "SELECT o.id FROM outer_rows o WHERE o.value IN "
        + "(SELECT i.value FROM inner_rows i WHERE i.region=1)";

    PlanRows plain = plan(fixture.session(), "EXPLAIN " + uncorrelated);
    PlanRows analyzed = plan(fixture.session(), "EXPLAIN ANALYZE " + uncorrelated);
    assertEquals(plain.count, analyzed.count);
    for (int step = 0; step < plain.count; step++) {
      assertEquals(plain.operators[step], analyzed.operators[step]);
      assertEquals(plain.details[step], analyzed.details[step]);
    }
    int edge = analyzed.count - 6;
    assertEdgeShape(plain, edge, false, "member", "index", 2, 0);
    assertEdgeShape(analyzed, edge, true, "member", "index", 2, 0);
    assertEquals(edgeDetail(0, 1, 2, false), analyzed.details[edge]);
    assertRows(analyzed, edge, 4, 1, 2, 2, 4, 1);
    assertEquals(3, analyzed.rows[edge] - analyzed.rows[edge + 1], "cache hits");

    String correlated = "SELECT o.id FROM outer_rows o WHERE o.value IN "
        + "(SELECT i.value FROM inner_rows i WHERE i.region=o.region)";
    PlanRows correlatedPlan = plan(
        fixture.session(), "EXPLAIN ANALYZE " + correlated);
    edge = correlatedPlan.count - 6;
    assertTrue((correlatedPlan.details[edge] & CORRELATED) != 0);
    assertEdgeShape(correlatedPlan, edge, true, "member", "index", 2, 0);
    assertEquals(edgeDetail(0, 1, 2, true), correlatedPlan.details[edge]);
    assertRows(correlatedPlan, edge, 4, 4, 6, 6, 4, 1);

    assertCounters(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT i.value FROM inner_rows i LIMIT 0)",
        4, 1, 0, 0, 4, 0);
    assertCounters(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT i.value FROM inner_rows i LIMIT 1)",
        4, 1, 1, 1, 4, 0);
    assertCounters(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i)",
        4, 1, 1, 1, 4, 4);
    assertCounters(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE o.id=1 OR EXISTS "
            + "(SELECT i.id FROM inner_rows i)",
        3, 1, 1, 1, 3, 4);

    PlanRows scalar = plan(
        fixture.session(),
        "EXPLAIN ANALYZE SELECT o.id FROM outer_rows o WHERE o.value="
            + "(SELECT s.value FROM scalar_rows s WHERE s.owner=1)");
    edge = scalar.count - 6;
    assertEdgeShape(scalar, edge, true, "scalar", "table", -1, 0);
    assertEquals(edgeDetail(0, 1, 2, false), scalar.details[edge]);
    assertRows(scalar, edge, 4, 1, 3, 1, 4, 1);

    PlanRows joined = plan(
        fixture.session(),
        "EXPLAIN ANALYZE SELECT o.id FROM outer_rows o "
            + "JOIN scalar_rows s ON o.id=s.owner WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i WHERE i.value=o.value)");
    edge = joined.count - 6;
    assertEdgeShape(joined, edge, true, "exists", "table", -1, 1);
    assertEquals(3, joined.rows[edge]);
    assertEquals(1, joined.rows[edge + 5]);
    fixture.close();
  }

  @Test
  void reportsDescendantOnlyCorrelationFromTheCacheAuthority(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    String outsideChild = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT m.id FROM inner_rows m WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE i.region=o.region))";
    PlanRows correlated = plan(
        fixture.session(), "EXPLAIN ANALYZE " + outsideChild);
    int outer = edgeStep(correlated, 0, 1);
    assertTrue(outer >= 0);
    assertEquals(edgeDetail(0, 1, 2, true), correlated.details[outer]);
    assertTrue((correlated.details[outer] & CORRELATED) != 0);
    assertEquals(4, correlated.rows[outer]);
    assertEquals(4, correlated.rows[outer + 1]);

    String insideChild = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT m.id FROM inner_rows m WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE i.region=m.region))";
    PlanRows cacheable = plan(
        fixture.session(), "EXPLAIN ANALYZE " + insideChild);
    outer = edgeStep(cacheable, 0, 1);
    assertTrue(outer >= 0);
    assertEquals(edgeDetail(0, 1, 2, false), cacheable.details[outer]);
    assertFalse((cacheable.details[outer] & CORRELATED) != 0);
    assertEquals(4, cacheable.rows[outer]);
    assertEquals(1, cacheable.rows[outer + 1]);
    fixture.close();
  }

  @Test
  void plainExplainPreflightsButNeverExecutesTemporalProjection(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute(
        "CREATE TABLE temporal_plan (id BIGINT PRIMARY KEY,observed TIMESTAMP(6))");
    fixture.execute(
        "INSERT INTO temporal_plan VALUES "
            + "(1,TIMESTAMP '2024-03-31 01:30:00')");
    String gap = "SELECT o.id FROM outer_rows o WHERE "
        + "TIMESTAMP WITH TIME ZONE '2024-03-31 01:30:00+00:00'="
        + "(SELECT t.observed AT TIME ZONE 'Europe/London' "
        + "FROM temporal_plan t WHERE t.id=1)";
    PlanRows plain = plan(fixture.session(), "EXPLAIN " + gap);
    int edge = plain.count - 6;
    for (int phase = 0; phase < 6; phase++) assertTrue(plain.nulls[edge + phase]);

    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        fixture.session().beginScan("EXPLAIN ANALYZE " + gap, cursor));
    assertEquals(StatusCode.OK, cursor.reset());
    SqlScanCursor invalidZone = new SqlScanCursor();
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        fixture.session().beginScan(
            "EXPLAIN SELECT o.id FROM outer_rows o WHERE "
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'="
                + "(SELECT t.observed AT TIME ZONE 'No/Such' FROM temporal_plan t)",
            invalidZone));
    assertEquals(StatusCode.OK, invalidZone.reset());
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);
    fixture.close();
  }

  private static void assertCounters(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long invocations,
      long executions,
      long candidates,
      long accepted,
      long results,
      long parentAccepted) {
    PlanRows plan = plan(fixture.session(), "EXPLAIN ANALYZE " + sql);
    int edge = plan.count - 6;
    assertRows(
        plan, edge,
        invocations, executions, candidates, accepted, results, parentAccepted);
  }

  private static void assertEdgeShape(
      PlanRows plan,
      int edge,
      boolean analyzed,
      String kind,
      String access,
      long column,
      int filterLeaves) {
    assertEquals(PackedText.pack(kind), plan.operators[edge]);
    assertEquals(PackedText.pack("execute"), plan.operators[edge + 1]);
    assertEquals(PackedText.pack(access), plan.operators[edge + 2]);
    assertEquals(PackedText.pack("filter"), plan.operators[edge + 3]);
    assertEquals(PackedText.pack("result"), plan.operators[edge + 4]);
    assertEquals(PackedText.pack("parent"), plan.operators[edge + 5]);
    assertEquals(1, plan.details[edge + 1]);
    assertEquals(column, plan.details[edge + 2]);
    assertEquals(filterLeaves, plan.details[edge + 3], kind + "/" + access);
    assertEquals(1, plan.details[edge + 4]);
    assertEquals(0, plan.details[edge + 5]);
    for (int phase = 0; phase < 6; phase++) {
      assertEquals(!analyzed, plan.nulls[edge + phase]);
    }
  }

  private static long edgeDetail(
      int parent, int child, int depth, boolean correlated) {
    long detail = parent | (long) child << 6 | (long) depth << 12;
    return correlated ? detail | CORRELATED : detail;
  }

  private static void assertRows(PlanRows plan, int edge, long... expected) {
    for (int phase = 0; phase < expected.length; phase++) {
      assertEquals(expected[phase], plan.rows[edge + phase], "phase " + phase);
    }
  }

  private static int edgeStep(PlanRows plan, int parent, int child) {
    for (int step = 0; step < plan.count; step++) {
      long operator = plan.operators[step];
      if (operator != PackedText.pack("exists")
          && operator != PackedText.pack("scalar")
          && operator != PackedText.pack("member")) continue;
      long detail = plan.details[step];
      if ((detail & 63) == parent && (detail >> 6 & 63) == child) return step;
    }
    return -1;
  }

  private static PlanRows plan(SqlSession session, String sql) {
    PlanRows plan = new PlanRows();
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    StatusCode status;
    while ((status = session.nextScan(cursor, row)) == StatusCode.OK) {
      plan.operators[plan.count] = row.valueAt(0);
      plan.details[plan.count] = row.valueAt(1);
      plan.rows[plan.count] = row.valueAt(2);
      plan.nulls[plan.count] = row.isNull(2);
      plan.count++;
    }
    assertEquals(StatusCode.CONFLICT, status, sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
    return plan;
  }

  private static final class PlanRows {
    private final long[] operators = new long[256];
    private final long[] details = new long[256];
    private final long[] rows = new long[256];
    private final boolean[] nulls = new boolean[256];
    private int count;
  }
}
