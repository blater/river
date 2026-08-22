package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public-session contract for predicate-subquery rows consumed through P3. */
final class SqlSubqueryPipelineConsumerTest {
  private static final String TABLE_GRAPH =
      "(o.value IN (SELECT i.value FROM inner_rows i WHERE i.region=o.region) "
          + "OR o.id=3 OR o.id=4)";

  @Test
  void matchesDirectTableRowsThroughParentPipeline(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);

    assertRows(
        fixture,
        "SELECT o.id,o.value,o.label FROM outer_rows o WHERE " + TABLE_GRAPH,
        new long[] {1, 3, 4},
        new long[] {10, 0, 40},
        new boolean[] {false, true, false},
        new String[] {"first", "unknown", "東京-🌊-résumé"});
    String direct = "SELECT o.id,o.value,o.label FROM outer_rows o WHERE "
        + TABLE_GRAPH + " AND o.id>=3";
    String pipelined = "SELECT d.id,d.value,d.label FROM "
        + "(SELECT o.id,o.value,o.label FROM outer_rows o WHERE "
        + TABLE_GRAPH + ") d WHERE d.id>=3";
    long[] ids = {3, 4};
    long[] values = {0, 40};
    boolean[] nulls = {true, false};
    String[] labels = {"unknown", "東京-🌊-résumé"};
    assertRows(fixture, direct, ids, values, nulls, labels);
    assertRows(fixture, pipelined, ids, values, nulls, labels);
    String directNullableParent = "SELECT o.id,o.value,o.label FROM outer_rows o WHERE "
        + TABLE_GRAPH + " AND o.value>=40";
    String pipelinedNullableParent = "SELECT d.id,d.value,d.label FROM "
        + "(SELECT o.id,o.value,o.label FROM outer_rows o WHERE "
        + TABLE_GRAPH + ") d WHERE d.value>=40";
    long[] acceptedId = {4};
    long[] acceptedValue = {40};
    boolean[] acceptedNull = {false};
    String[] acceptedLabel = {"東京-🌊-résumé"};
    assertRows(
        fixture,
        directNullableParent,
        acceptedId,
        acceptedValue,
        acceptedNull,
        acceptedLabel);
    assertRows(
        fixture,
        pipelinedNullableParent,
        acceptedId,
        acceptedValue,
        acceptedNull,
        acceptedLabel);

    fixture.close();
  }

  @Test
  void matchesDirectJoinedRowsThroughParentPipeline(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute(
        "CREATE TABLE pipeline_left (id BIGINT PRIMARY KEY,label VARCHAR(32))");
    fixture.execute(
        "CREATE TABLE pipeline_right "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,score BIGINT)");
    fixture.execute(
        "CREATE TABLE pipeline_allow "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,score BIGINT)");
    fixture.execute(
        "INSERT INTO pipeline_left VALUES "
            + "(1,'東京'),(2,'🌊-résumé'),(3,'rejected')");
    fixture.execute(
        "INSERT INTO pipeline_right VALUES "
            + "(10,1,30),(11,1,10),(12,2,20),(13,3,40)");
    fixture.execute(
        "INSERT INTO pipeline_allow VALUES "
            + "(1,1,30),(2,2,20),(3,3,NULL)");

    String graph = "r.score IN (SELECT a.score FROM pipeline_allow a "
        + "WHERE a.owner=l.id)";
    String direct = "SELECT l.id AS lid,r.id AS rid,r.score AS score,l.label AS label "
        + "FROM pipeline_left l JOIN pipeline_right r ON l.id=r.owner WHERE "
        + graph + " AND r.score>=20";
    String pipelined = "SELECT d.lid,d.rid,d.score,d.label FROM "
        + "(SELECT l.id AS lid,r.id AS rid,r.score AS score,l.label AS label "
        + "FROM pipeline_left l JOIN pipeline_right r ON l.id=r.owner WHERE "
        + graph + ") d WHERE d.score>=20";
    assertJoinedRows(fixture, direct);
    assertJoinedRows(fixture, pipelined);
    long[] counters = edgeCounters(fixture, "EXPLAIN ANALYZE " + pipelined);
    assertEquals(4, counters[0], "one graph invocation per joined composite");
    assertEquals(4, counters[1], "the correlated child executes per composite");
    assertEquals(12, counters[2], "four complete three-row child scans");
    assertEquals(4, counters[3], "one child owner matches each composite");
    assertEquals(4, counters[4], "every invoked membership produces a result");
    assertEquals(2, counters[5], "two joined composites pass the graph once");

    fixture.close();
  }

  @Test
  void aggregatesComputedValuesAboveTheSameGraphSource(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("INSERT INTO outer_rows VALUES (5,10,2,'duplicate')");
    String direct = "SELECT SUM(value+1) FROM outer_rows o WHERE " + TABLE_GRAPH;
    String pipelined = "SELECT SUM(value+1) FROM "
        + "(SELECT o.value FROM outer_rows o WHERE " + TABLE_GRAPH + ") d";

    fixture.assertRows(direct, 63);
    fixture.assertRows(pipelined, 63);

    fixture.close();
  }

  @Test
  void groupsHavingAndDistinctMatchAboveTheSameGraphSource(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("INSERT INTO outer_rows VALUES (5,10,2,'duplicate')");
    String directGroup = "SELECT region+10 AS bucket,COUNT(*) "
        + "FROM outer_rows o WHERE " + TABLE_GRAPH
        + " GROUP BY region+10 HAVING COUNT(*)=1";
    String pipelinedGroup = "SELECT region+10 AS bucket,COUNT(*) FROM "
        + "(SELECT o.region FROM outer_rows o WHERE " + TABLE_GRAPH + ") d "
        + "GROUP BY region+10 HAVING COUNT(*)=1";
    assertPairs(fixture, directGroup, new long[] {11, 13}, 1);
    assertPairs(fixture, pipelinedGroup, new long[] {11, 13}, 1);

    String directDistinct = "SELECT DISTINCT region+10 AS bucket "
        + "FROM outer_rows o WHERE " + TABLE_GRAPH;
    String pipelinedDistinct = "SELECT DISTINCT region+10 AS bucket FROM "
        + "(SELECT o.region FROM outer_rows o WHERE " + TABLE_GRAPH + ") d";
    fixture.assertRows(directDistinct, 11, 12, 13);
    fixture.assertRows(pipelinedDistinct, 11, 12, 13);

    fixture.close();
  }

  @Test
  void ordersComputedValuesAboveTheSameGraphSource(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("INSERT INTO outer_rows VALUES (5,10,2,'duplicate')");
    String direct = "SELECT o.id,o.value+1 AS sorted,o.label "
        + "FROM outer_rows o WHERE " + TABLE_GRAPH + " ORDER BY sorted";
    String pipelined = "SELECT d.id,d.value+1 AS sorted,d.label FROM "
        + "(SELECT o.id,o.value,o.label FROM outer_rows o WHERE "
        + TABLE_GRAPH + ") d ORDER BY sorted";
    long[] ids = {3, 1, 5, 4};
    long[] values = {0, 11, 11, 41};
    boolean[] nulls = {true, false, false, false};
    String[] labels = {"unknown", "first", "duplicate", "東京-🌊-résumé"};

    assertRows(fixture, direct, ids, values, nulls, labels);
    assertRows(fixture, pipelined, ids, values, nulls, labels);

    fixture.close();
  }

  @Test
  void countsTheDeepestGraphOnceBeforeTheParentPredicate(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    String pipeline = "SELECT d.id FROM "
        + "(SELECT o.id,o.value FROM outer_rows o WHERE " + TABLE_GRAPH + ") d "
        + "WHERE d.id>=3";

    long[] counters = edgeCounters(fixture, "EXPLAIN ANALYZE " + pipeline);
    assertEquals(4, counters[0], "one graph invocation per physical outer row");
    assertEquals(4, counters[1], "the correlated child executes per invocation");
    assertEquals(16, counters[2], "four complete four-row child scans");
    assertEquals(6, counters[3], "six child candidates match their region");
    assertEquals(4, counters[4], "every invoked membership produces a result");
    assertEquals(3, counters[5], "deepest graph accepts before parent filtering");
    fixture.assertRows(pipeline, 3, 4);

    fixture.close();
  }

  @Test
  void discardsPrivateRowsOnTerminalFailureAndReusesEveryTopology(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    String failure = "SELECT d.id FROM (SELECT o.id FROM outer_rows o WHERE "
        + "o.id=1 OR o.value=(SELECT s.value FROM scalar_rows s "
        + "WHERE s.owner=o.id)) d";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();

    for (int attempt = 0; attempt < 2; attempt++) {
      assertEquals(StatusCode.OK, cursor.reset());
      row.reset();
      fixture.result().reset();
      assertEquals(
          StatusCode.CARDINALITY_VIOLATION,
          fixture.session().beginScan(failure, cursor),
          failure);
      assertFalse(cursor.isActive(), failure);
      assertFalse(row.isAvailable(), failure);
      assertFalse(fixture.result().hasValue(), failure);
      assertEquals(0, fixture.result().affectedRows(), failure);
    }
    assertEquals(StatusCode.OK, cursor.reset());

    String graph = "SELECT d.id FROM "
        + "(SELECT o.id FROM outer_rows o WHERE " + TABLE_GRAPH + ") d";
    fixture.assertRows(graph, 1, 3, 4);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE " + TABLE_GRAPH,
        1, 3, 4);
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);
    fixture.assertRows(graph, 1, 3, 4);

    fixture.close();
  }

  @Test
  void keepsCardinalityStagesAndPipelinesOutOfChildBlocks(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);

    assertBegin(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT COUNT(*) FROM inner_rows i)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT region,COUNT(*) FROM inner_rows i GROUP BY region)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT DISTINCT i.region FROM inner_rows i)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i ORDER BY i.id)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT d.id FROM (SELECT i.id FROM inner_rows i) d)",
        StatusCode.FEATURE_NOT_SUPPORTED);

    fixture.close();
  }

  private static void assertRows(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] ids,
      long[] values,
      boolean[] nulls,
      String[] labels) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (int index = 0; index < ids.length; index++) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(ids[index], row.valueAt(0), sql);
      assertEquals(values[index], row.valueAt(1), sql);
      assertEquals(nulls[index], row.isNull(1), sql);
      assertEquals(labels[index], textAt(row, 2), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static void assertPairs(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] keys,
      long expectedValue) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (long key : keys) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(key, row.valueAt(0), sql);
      assertEquals(expectedValue, row.valueAt(1), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static long[] edgeCounters(
      SqlSubqueryAcceptanceFixture fixture, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    long[] counters = new long[6];
    int phase = -1;
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    StatusCode status;
    while ((status = fixture.session().nextScan(cursor, row)).isOk()) {
      long operator = row.valueAt(0);
      if (operator == PackedText.pack("exists")
          || operator == PackedText.pack("member")
          || operator == PackedText.pack("scalar")) phase = 0;
      if (phase >= 0 && phase < counters.length) counters[phase++] = row.valueAt(2);
    }
    assertEquals(StatusCode.CONFLICT, status, sql);
    assertEquals(6, phase, sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
    return counters;
  }

  private static void assertJoinedRows(
      SqlSubqueryAcceptanceFixture fixture, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    assertJoinedRow(fixture, cursor, row, 1, 10, 30, "東京", sql);
    assertJoinedRow(fixture, cursor, row, 2, 12, 20, "🌊-résumé", sql);
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static void assertJoinedRow(
      SqlSubqueryAcceptanceFixture fixture,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      long left,
      long right,
      long score,
      String label,
      String sql) {
    assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
    assertEquals(left, row.valueAt(0), sql);
    assertEquals(right, row.valueAt(1), sql);
    assertEquals(score, row.valueAt(2), sql);
    assertEquals(label, textAt(row, 3), sql);
  }

  private static String textAt(SqlScanRowResult row, int column) {
    if (row.isNull(column)) return null;
    char[] text = new char[64];
    int length = row.copyTextAt(column, text, 0);
    assertTrue(length >= 0);
    return new String(text, 0, length);
  }

  private static void assertBegin(
      SqlSubqueryAcceptanceFixture fixture, String sql, StatusCode expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(expected, fixture.session().beginScan(sql, cursor), sql);
    assertFalse(cursor.isActive(), sql);
    assertEquals(StatusCode.OK, cursor.reset(), sql);
  }
}
