package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public-session contract for grouped and distinct predicate-subquery consumers. */
final class SqlSubqueryGroupedConsumerTest {
  private static final String GRAPH =
      "o.amount IN (SELECT c.value FROM group_child c WHERE c.owner=o.id)";

  @Test
  void filtersOrderedAndMaterializedGroupsExactlyOnce(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = groupedFixture(root);

    assertGroups(
        fixture,
        "SELECT bucket, SUM(amount) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket",
        new long[] {10, 20},
        new long[] {30, 170});
    assertGroups(
        fixture,
        "SELECT material_bucket, SUM(amount) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY material_bucket",
        new long[] {10, 20},
        new long[] {30, 170});
    assertGroups(
        fixture,
        "SELECT nullable_key, COUNT(*) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY nullable_key",
        new long[] {0, 1, 2},
        new long[] {1, 2, 2},
        1L);
    assertDistinctLongs(
        fixture,
        "SELECT DISTINCT bucket FROM group_rows o WHERE " + GRAPH,
        new long[] {10, 20},
        0);
    assertGroups(
        fixture,
        "SELECT bucket, COUNT(label) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket",
        new long[] {10, 20},
        new long[] {2, 2});

    assertGroups(
        fixture,
        "SELECT bucket, COUNT(*) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket HAVING COUNT(*)>2",
        new long[] {20},
        new long[] {3});
    assertGroups(
        fixture,
        "SELECT bucket, COUNT(*) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket HAVING COUNT(*)<0",
        new long[0],
        new long[0]);
    assertGroups(
        fixture,
        "SELECT bucket, COUNT(*) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket HAVING COUNT(*)=NULL",
        new long[0],
        new long[0]);

    fixture.close();
  }

  @Test
  void copiesUnicodeKeysAndAggregateValuesBeforeReleasingGraphRows(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = groupedFixture(root);

    Map<String, Long> labels = textGroups(
        fixture,
        "SELECT label, COUNT(*) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY label");
    assertEquals(4, labels.size());
    assertEquals(1, labels.get(null));
    assertEquals(2, labels.get("東京-🌊"));
    assertEquals(1, labels.get("résumé"));
    assertEquals(1, labels.get("🌊"));
    assertFalse(labels.containsKey("rejected"));
    assertFalse(labels.containsKey("unknown"));

    assertTextAggregates(
        fixture,
        "SELECT bucket, MIN(label) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket",
        new long[] {10, 20},
        new String[] {"東京-🌊", "résumé"});
    assertTextAggregates(
        fixture,
        "SELECT bucket, MAX(label) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket",
        new long[] {10, 20},
        new String[] {"東京-🌊", "🌊"});

    assertDistinctLongs(
        fixture,
        "SELECT DISTINCT nullable_key FROM group_rows o WHERE " + GRAPH,
        new long[] {0, 1, 2},
        1L);
    Map<String, Long> distinctLabels = distinctText(
        fixture,
        "SELECT DISTINCT label FROM group_rows o WHERE " + GRAPH);
    assertEquals(4, distinctLabels.size());
    assertTrue(distinctLabels.containsKey(null));
    assertTrue(distinctLabels.containsKey("東京-🌊"));
    assertTrue(distinctLabels.containsKey("résumé"));
    assertTrue(distinctLabels.containsKey("🌊"));

    fixture.close();
  }

  @Test
  void admitsDirectAndP3ComputedConsumersButKeepsChildShapesClosed(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = groupedFixture(root);

    assertGroups(
        fixture,
        "SELECT bucket+1, COUNT(*) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket+1",
        new long[] {11, 21},
        new long[] {2, 3});
    assertGroups(
        fixture,
        "SELECT bucket, SUM(amount+1) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket",
        new long[] {10, 20},
        new long[] {32, 173});
    assertDistinctLongs(
        fixture,
        "SELECT DISTINCT bucket+1 FROM group_rows o WHERE " + GRAPH,
        new long[] {11, 21},
        0);
    assertGroups(
        fixture,
        "SELECT bucket, SUM(amount) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket HAVING ABS(SUM(amount))+1>100",
        new long[] {20},
        new long[] {170});

    assertBegin(
        fixture,
        "SELECT bucket, COUNT(*) FROM group_rows o GROUP BY bucket "
            + "HAVING EXISTS (SELECT c.id FROM group_child c WHERE c.owner=o.bucket)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT o.id FROM group_rows o WHERE EXISTS "
            + "(SELECT COUNT(*) FROM group_child c)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    fixture.assertRows(
        "SELECT o.id,o.bucket FROM group_rows o WHERE EXISTS "
            + "(SELECT c.id FROM group_child c)",
        1, 2, 3, 4, 5, 6, 7);
    assertBegin(
        fixture,
        "SELECT o.id,o.bucket FROM group_rows o WHERE EXISTS "
            + "(SELECT owner,COUNT(*) FROM group_child c GROUP BY owner)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT o.id FROM group_rows o WHERE EXISTS "
            + "(SELECT DISTINCT c.owner FROM group_child c)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT o.id FROM group_rows o WHERE EXISTS "
            + "(SELECT c.id FROM group_child c ORDER BY c.id)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertGroups(
        fixture,
        "SELECT d.bucket+1, COUNT(*) FROM "
            + "(SELECT o.bucket FROM group_rows o WHERE " + GRAPH + ") d "
            + "GROUP BY d.bucket+1",
        new long[] {11, 21},
        new long[] {2, 3});
    assertDistinctLongs(
        fixture,
        "SELECT DISTINCT d.bucket+1 FROM "
            + "(SELECT o.bucket FROM group_rows o WHERE " + GRAPH + ") d",
        new long[] {11, 21},
        0);

    fixture.close();
  }

  @Test
  void latchesNestedFailureWithoutPublishingPartialGroupOrDistinct(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = groupedFixture(root);
    String failure = "o.id=1 OR o.amount=(SELECT e.value FROM group_error e "
        + "WHERE e.owner=o.id)";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();

    assertEquals(
        StatusCode.OK,
        fixture.session().beginScan(
            "SELECT bucket, SUM(amount) FROM group_rows o WHERE " + failure
                + " GROUP BY bucket",
            cursor));
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()));

    for (int attempt = 0; attempt < 2; attempt++) {
      assertEquals(StatusCode.OK, cursor.reset());
      assertEquals(
          StatusCode.CARDINALITY_VIOLATION,
          fixture.session().beginScan(
              "SELECT DISTINCT label FROM group_rows o WHERE " + failure,
              cursor));
      assertFalse(cursor.isActive());
      assertFalse(row.isAvailable());
    }
    assertEquals(StatusCode.OK, cursor.reset());

    assertGroups(
        fixture,
        "SELECT bucket, COUNT(*) FROM group_rows WHERE id<=2 GROUP BY bucket",
        new long[] {10},
        new long[] {2});
    assertGroups(
        fixture,
        "SELECT bucket, COUNT(*) FROM group_rows o WHERE " + GRAPH
            + " GROUP BY bucket",
        new long[] {10, 20},
        new long[] {2, 3});
    assertDistinctLongs(
        fixture,
        "SELECT DISTINCT bucket FROM group_rows o WHERE " + GRAPH,
        new long[] {10, 20},
        0);

    fixture.close();
  }

  private static SqlSubqueryAcceptanceFixture groupedFixture(Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute(
        "CREATE TABLE group_rows (id BIGINT PRIMARY KEY,bucket BIGINT NOT NULL,"
            + "material_bucket BIGINT,nullable_key BIGINT,amount BIGINT,"
            + "label VARCHAR(32))");
    fixture.execute(
        "CREATE TABLE group_child "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,value BIGINT)");
    fixture.execute(
        "CREATE TABLE group_error "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,value BIGINT)");
    fixture.execute(
        "INSERT INTO group_rows VALUES "
            + "(1,10,10,1,10,'東京-🌊'),"
            + "(2,10,10,1,20,'東京-🌊'),"
            + "(3,20,20,2,30,'unknown'),"
            + "(4,20,20,NULL,40,NULL),"
            + "(5,30,30,NULL,50,'rejected'),"
            + "(6,20,20,2,60,'résumé'),"
            + "(7,20,20,2,70,'🌊')");
    fixture.execute(
        "INSERT INTO group_child VALUES "
            + "(1,1,10),(2,2,20),(3,3,NULL),(4,4,40),(5,6,60),(6,7,70)");
    fixture.execute(
        "INSERT INTO group_error VALUES (1,1,10),(2,2,20),(3,2,21)");
    fixture.execute("CREATE INDEX group_rows_bucket ON group_rows(bucket)");
    return fixture;
  }

  private static void assertGroups(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] keys,
      long[] values) {
    assertGroups(fixture, sql, keys, values, 0);
  }

  private static void assertGroups(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] keys,
      long[] values,
      long nullKeys) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (int index = 0; index < keys.length; index++) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(keys[index], row.valueAt(0), sql);
      assertEquals(values[index], row.valueAt(1), sql);
      assertEquals((nullKeys & 1L << index) != 0, row.isNull(0), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertFalse(row.isAvailable(), sql);
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()),
        sql);
  }

  private static Map<String, Long> textGroups(
      SqlSubqueryAcceptanceFixture fixture, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    Map<String, Long> values = new HashMap<>();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    StatusCode status;
    while ((status = fixture.session().nextScan(cursor, row)).isOk()) {
      values.put(textAt(row, 0), row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, status, sql);
    assertFalse(row.isAvailable(), sql);
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()),
        sql);
    return values;
  }

  private static Map<String, Long> distinctText(
      SqlSubqueryAcceptanceFixture fixture, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    Map<String, Long> values = new HashMap<>();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    StatusCode status;
    while ((status = fixture.session().nextScan(cursor, row)).isOk()) {
      String value = textAt(row, 0);
      values.put(value, values.getOrDefault(value, 0L) + 1);
    }
    assertEquals(StatusCode.CONFLICT, status, sql);
    assertFalse(row.isAvailable(), sql);
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()),
        sql);
    for (long count : values.values()) assertEquals(1, count, sql);
    return values;
  }

  private static void assertTextAggregates(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] keys,
      String[] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (int index = 0; index < keys.length; index++) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(keys[index], row.valueAt(0), sql);
      assertEquals(expected[index], textAt(row, 1), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()),
        sql);
  }

  private static void assertDistinctLongs(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] expected,
      long nullValues) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (int index = 0; index < expected.length; index++) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(expected[index], row.valueAt(0), sql);
      assertEquals((nullValues & 1L << index) != 0, row.isNull(0), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(
        StatusCode.OK,
        fixture.session().closeScan(cursor, fixture.result()),
        sql);
  }

  private static String textAt(SqlScanRowResult row, int index) {
    if (row.isNull(index)) return null;
    char[] text = new char[64];
    int length = row.copyTextAt(index, text, 0);
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
