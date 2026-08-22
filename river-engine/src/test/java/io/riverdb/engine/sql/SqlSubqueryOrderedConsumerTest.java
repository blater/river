package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlCommand;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public-session contract for ordered and spilled predicate-subquery consumers. */
final class SqlSubqueryOrderedConsumerTest {
  private static final String SMALL_GRAPH =
      "o.rank IN (SELECT c.marker FROM order_child c WHERE c.owner=o.id)";
  private static final String SMALL_ORDINARY = "o.id IN (1,2,5,6)";

  @Test
  void ordersIndexedAndMaterializedRowsWithOwnedUnicode(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = smallFixture(root);

    String indexed =
        "SELECT id FROM order_rows o WHERE " + SMALL_GRAPH + " ORDER BY rank";
    assertPlanSort(indexed, fixture, false);
    assertIds(fixture, indexed, 2, 1, 5, 6);
    assertIds(
        fixture,
        "SELECT id FROM order_rows o WHERE " + SMALL_ORDINARY + " ORDER BY rank",
        2, 1, 5, 6);

    String nullable = "SELECT id FROM order_rows o WHERE " + SMALL_GRAPH
        + " ORDER BY nullable_rank";
    assertPlanSort(nullable, fixture, true);
    assertIds(fixture, nullable, 6, 2, 1, 5);
    assertIds(
        fixture,
        "SELECT id FROM order_rows o WHERE " + SMALL_ORDINARY
            + " ORDER BY nullable_rank",
        6, 2, 1, 5);
    assertIds(
        fixture,
        "SELECT id FROM order_rows o WHERE " + SMALL_GRAPH
            + " ORDER BY nullable_rank DESC LIMIT 2",
        5, 1);
    long[] counters = edgeCounters(fixture, "EXPLAIN ANALYZE " + nullable);
    assertEquals(6, counters[0], "one invocation per table candidate");
    assertEquals(4, counters[5], "four accepted table rows");

    assertTextRows(
        fixture,
        "SELECT id,label FROM order_rows o WHERE " + SMALL_GRAPH
            + " ORDER BY label",
        new long[] {2, 5, 1, 6},
        new String[] {null, "alpha", "東京", "🌊-résumé"});
    assertTextRows(
        fixture,
        "SELECT id,label FROM order_rows o WHERE " + SMALL_ORDINARY
            + " ORDER BY label",
        new long[] {2, 5, 1, 6},
        new String[] {null, "alpha", "東京", "🌊-résumé"});
    assertBegin(
        fixture,
        "SELECT id,CAST(day_value AS VARCHAR(10)) AS rendered "
            + "FROM order_rows o WHERE " + SMALL_ORDINARY
            + " ORDER BY rendered DESC LIMIT 3",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertTextRows(
        fixture,
        "SELECT id,CAST(day_value AS VARCHAR(10)) AS rendered "
            + "FROM order_rows o WHERE " + SMALL_GRAPH
            + " ORDER BY nullable_rank DESC LIMIT 3",
        new long[] {5, 1, 2},
        new String[] {"2024-01-05", "2024-01-03", "2024-01-01"});
    assertFixedRows(
        fixture,
        "SELECT id,rank+1 AS next_rank FROM order_rows o WHERE " + SMALL_GRAPH
            + " ORDER BY next_rank DESC LIMIT 2",
        new long[] {6, 5},
        new long[] {61, 51});

    fixture.close();
  }

  @Test
  void preservesOneGraphFilterAcrossJoinedOrdering(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = smallFixture(root);
    fixture.execute(
        "CREATE TABLE order_left "
            + "(id BIGINT PRIMARY KEY,join_key BIGINT,label VARCHAR(32))");
    fixture.execute(
        "CREATE TABLE order_right "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,score BIGINT)");
    fixture.execute(
        "CREATE TABLE order_join_allow "
            + "(id BIGINT PRIMARY KEY,owner BIGINT)");
    fixture.execute(
        "INSERT INTO order_left VALUES "
            + "(1,10,'東京'),(2,20,'🌊'),(3,30,'rejected')");
    fixture.execute(
        "INSERT INTO order_right VALUES "
            + "(10,1,10),(11,1,30),(12,2,20),(13,3,40)");
    fixture.execute("INSERT INTO order_join_allow VALUES (1,1),(2,2)");
    String ordered = "SELECT l.id AS lid,r.id AS rid,r.score AS score "
        + "FROM order_left l JOIN order_right r ON l.id=r.owner WHERE EXISTS "
        + "(SELECT a.id FROM order_join_allow a WHERE a.owner=l.id) "
        + "ORDER BY score DESC";

    assertTriples(
        fixture,
        ordered,
        new long[][] {{1, 11, 30}, {2, 12, 20}, {1, 10, 10}});
    long[] counters = edgeCounters(fixture, "EXPLAIN ANALYZE " + ordered);
    assertEquals(4, counters[0], "one invocation per joined candidate");
    assertEquals(3, counters[5], "three accepted parent composites");

    fixture.close();
  }

  @Test
  void spillsOwnedUnicodeWithTheSameCommonOrderAsMemory(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = spillFixture(root);
    String inMemory = spillQuery(1);
    String spilled = spillQuery(2);
    assertPlanSort(inMemory, fixture, true);
    assertPlanSort(spilled, fixture, true);

    assertSpillRows(fixture, inMemory, 1_000);
    assertSpillRows(fixture, spilled, 1_100);

    fixture.close();
  }

  @Test
  void discardsPrivateMemoryAndSpillRowsOnNestedFailure(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = spillFixture(root);
    String inMemoryFailure = "SELECT id,label FROM spill_rows o WHERE o.id=1 OR "
        + "o.rank=(SELECT e.value FROM spill_error e WHERE e.owner=o.id) "
        + "ORDER BY rank";
    String spillFailure = "SELECT id,label FROM spill_rows o WHERE o.id<=1025 OR "
        + "o.rank=(SELECT e.value FROM spill_error e WHERE e.owner=o.id) "
        + "ORDER BY rank";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();

    assertRepeatedBeginFailure(fixture, cursor, row, inMemoryFailure);
    assertIds(
        fixture,
        "SELECT id FROM spill_rows WHERE id<=2 ORDER BY rank",
        2, 1);
    assertRepeatedBeginFailure(fixture, cursor, row, spillFailure);
    assertIds(fixture, spillQuery(1) + " LIMIT 1", 1_000);
    assertIds(
        fixture,
        "SELECT id FROM spill_rows WHERE id<=2 ORDER BY rank",
        2, 1);

    fixture.close();
  }

  @Test
  void keepsChildAndP3OrderClosedWhilePreservingUnorderedP3(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = smallFixture(root);

    assertBegin(
        fixture,
        "SELECT o.id FROM order_rows o WHERE EXISTS "
            + "(SELECT c.id FROM order_child c WHERE c.owner=o.id ORDER BY c.id)",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertBegin(
        fixture,
        "SELECT rank,COUNT(*) FROM "
            + "(SELECT o.rank FROM order_rows o WHERE " + SMALL_GRAPH + ") d "
            + "GROUP BY rank",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertIds(
        fixture,
        "SELECT id FROM order_rows o WHERE " + SMALL_GRAPH + " ORDER BY rank",
        2, 1, 5, 6);
    assertBegin(
        fixture,
        "SELECT DISTINCT rank FROM "
            + "(SELECT o.rank FROM order_rows o WHERE " + SMALL_GRAPH + ") d",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertIds(
        fixture,
        "SELECT id FROM order_rows o WHERE " + SMALL_GRAPH + " ORDER BY rank",
        2, 1, 5, 6);
    assertBegin(
        fixture,
        "SELECT d.id FROM (SELECT o.id,o.rank FROM order_rows o WHERE "
            + SMALL_GRAPH + ") d ORDER BY rank",
        StatusCode.FEATURE_NOT_SUPPORTED);
    assertIds(
        fixture,
        "SELECT id FROM order_rows o WHERE " + SMALL_GRAPH + " ORDER BY rank",
        2, 1, 5, 6);
    fixture.close();
  }

  @Test
  void preservesUnorderedGraphP3(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = smallFixture(root);
    fixture.assertRows(
        "SELECT d.id FROM (SELECT o.id FROM order_rows o WHERE "
            + SMALL_GRAPH + ") d",
        1, 2, 5, 6);
    // P4C-7D owns successful graph-P3 to graph-consumer topology handoff.
    fixture.close();
  }

  private static SqlSubqueryAcceptanceFixture smallFixture(Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute(
        "CREATE TABLE order_rows (id BIGINT PRIMARY KEY,rank BIGINT NOT NULL,"
            + "nullable_rank BIGINT,label VARCHAR(32),day_value DATE)");
    fixture.execute(
        "CREATE TABLE order_child "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,marker BIGINT)");
    fixture.execute(
        "INSERT INTO order_rows VALUES "
            + "(1,30,30,'東京',DATE '2024-01-03'),"
            + "(2,10,20,NULL,DATE '2024-01-01'),"
            + "(3,20,25,'unknown',DATE '2024-01-02'),"
            + "(4,40,10,'rejected',DATE '2024-01-04'),"
            + "(5,50,40,'alpha',DATE '2024-01-05'),"
            + "(6,60,NULL,'🌊-résumé',DATE '2024-01-06')");
    fixture.execute(
        "INSERT INTO order_child VALUES "
            + "(1,1,30),(2,2,10),(3,3,NULL),(4,5,50),(5,6,60)");
    fixture.execute("CREATE INDEX order_rows_rank ON order_rows(rank)");
    return fixture;
  }

  private static SqlSubqueryAcceptanceFixture spillFixture(Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute(
        "CREATE TABLE spill_rows (id BIGINT PRIMARY KEY,rank BIGINT,label VARCHAR(32))");
    fixture.execute(
        "CREATE TABLE spill_accept "
            + "(id BIGINT PRIMARY KEY,mode BIGINT)");
    fixture.execute(
        "CREATE TABLE spill_error "
            + "(id BIGINT PRIMARY KEY,owner BIGINT,value BIGINT)");
    for (int first = 1; first <= 1_100; first += SqlCommand.MAXIMUM_INSERT_ROWS) {
      int end = Math.min(first + SqlCommand.MAXIMUM_INSERT_ROWS, 1_101);
      StringBuilder rows = new StringBuilder("INSERT INTO spill_rows VALUES ");
      for (int id = first; id < end; id++) {
        if (id > first) rows.append(',');
        rows.append('(').append(id).append(',');
        if (id == 1_000) rows.append("NULL");
        else rows.append(id <= 1_000 ? 1_001L - id : 2_000L + id);
        rows.append(",'").append(expectedLabel(id)).append("')");
      }
      fixture.execute(rows.toString());
    }
    for (int first = 1; first <= 1_100; first += SqlCommand.MAXIMUM_INSERT_ROWS) {
      int end = Math.min(first + SqlCommand.MAXIMUM_INSERT_ROWS, 1_101);
      StringBuilder accepted = new StringBuilder("INSERT INTO spill_accept VALUES ");
      for (int id = first; id < end; id++) {
        if (id > first) accepted.append(',');
        accepted.append('(').append(id).append(',')
            .append(id <= 1_000 ? 1 : 2).append(')');
      }
      fixture.execute(accepted.toString());
    }
    fixture.execute(
        "INSERT INTO spill_error VALUES "
            + "(1,2,10),(2,2,11),(3,1026,10),(4,1026,11)");
    return fixture;
  }

  private static String spillQuery(int mode) {
    return "SELECT id,label FROM spill_rows o WHERE EXISTS "
        + "(SELECT c.id FROM spill_accept c WHERE c.id=o.id AND c.mode<="
        + mode + ") ORDER BY rank";
  }

  private static String expectedLabel(int id) {
    return switch (id % 3) {
      case 0 -> "東京";
      case 1 -> "🌊-résumé";
      default -> "多🙂";
    };
  }

  private static void assertSpillRows(
      SqlSubqueryAcceptanceFixture fixture, String sql, int count) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (int ordinal = 0; ordinal < count; ordinal++) {
      int expected = ordinal < 1_000 ? 1_000 - ordinal : ordinal + 1;
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(expected, row.valueAt(0), sql);
      assertEquals(expectedLabel(expected), textAt(row, 1), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertFalse(row.isAvailable(), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static void assertRepeatedBeginFailure(
      SqlSubqueryAcceptanceFixture fixture,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      String sql) {
    for (int attempt = 0; attempt < 2; attempt++) {
      assertEquals(StatusCode.OK, cursor.reset());
      assertEquals(
          StatusCode.CARDINALITY_VIOLATION,
          fixture.session().beginScan(sql, cursor),
          sql);
      assertFalse(cursor.isActive(), sql);
      assertFalse(row.isAvailable(), sql);
    }
    assertEquals(StatusCode.OK, cursor.reset());
  }

  private static void assertIds(
      SqlSubqueryAcceptanceFixture fixture, String sql, long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (long id : expected) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(id, row.valueAt(0), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertFalse(row.isAvailable(), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static void assertFixedRows(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] ids,
      long[] values) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (int index = 0; index < ids.length; index++) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(ids[index], row.valueAt(0), sql);
      assertEquals(values[index], row.valueAt(1), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static void assertTextRows(
      SqlSubqueryAcceptanceFixture fixture,
      String sql,
      long[] ids,
      String[] values) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (int index = 0; index < ids.length; index++) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      assertEquals(ids[index], row.valueAt(0), sql);
      assertEquals(values[index], textAt(row, 1), sql);
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static void assertTriples(
      SqlSubqueryAcceptanceFixture fixture, String sql, long[][] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan(sql, cursor), sql);
    for (long[] tuple : expected) {
      assertEquals(StatusCode.OK, fixture.session().nextScan(cursor, row), sql);
      for (int column = 0; column < tuple.length; column++) {
        assertEquals(tuple[column], row.valueAt(column), sql);
      }
    }
    assertEquals(StatusCode.CONFLICT, fixture.session().nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()), sql);
  }

  private static void assertPlanSort(
      String sql, SqlSubqueryAcceptanceFixture fixture, boolean expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session().beginScan("EXPLAIN " + sql, cursor));
    boolean found = false;
    StatusCode status;
    while ((status = fixture.session().nextScan(cursor, row)).isOk()) {
      if (row.valueAt(0) == PackedText.pack("sort")) found = true;
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expected, found, sql);
    assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()));
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
