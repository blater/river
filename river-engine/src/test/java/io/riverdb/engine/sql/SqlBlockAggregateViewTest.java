package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
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

final class SqlBlockAggregateViewTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x424c4f434b504950L, 0x454c494e45303131L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final String HIGH_BMP = Character.toString(0xe000);

  @Test
  void executesAtomicAggregateAndDistinctStagesThroughPointAndStreaming(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = create(root);
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE tiny_keys (id VARCHAR(1) PRIMARY KEY, amount BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO tiny_keys VALUES ('a',1)", result));
    assertTextRows(
        session,
        result,
        "SELECT id FROM (SELECT id FROM tiny_keys ORDER BY id) ordered "
            + "WHERE id='ab'",
        new String[0]);

    assertRows(
        session,
        result,
        "SELECT category,total FROM "
            + "(SELECT category,SUM(amount) AS total FROM events "
            + "GROUP BY category HAVING COUNT(*)>0) grouped "
            + "ORDER BY category",
        new long[][] {{10, 300}, {20, 300}, {30, 0}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM events GROUP BY category) grouped "
            + "ORDER BY n",
        new long[][] {{1}, {1}, {2}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM events GROUP BY category "
            + "HAVING category>=20) grouped ORDER BY n",
        new long[][] {{1}, {1}});
    assertRows(
        session,
        result,
        "SELECT total FROM (SELECT SUM(amount) AS total FROM events) scalar "
            + "WHERE ABS(total)=600",
        new long[][] {{600}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM events) counted",
        new long[][] {{4}});
    assertRows(
        session,
        result,
        "SELECT id FROM (SELECT id FROM events LIMIT 1) limited",
        new long[][] {{1}});
    assertRows(
        session,
        result,
        "SELECT id FROM (SELECT id FROM events LIMIT 0) limited",
        new long[0][]);
    assertRows(
        session,
        result,
        "SELECT id FROM (SELECT id FROM events ORDER BY id DESC LIMIT 1) limited",
        new long[][] {{4}});
    assertRows(
        session,
        result,
        "SELECT id FROM (SELECT id FROM events ORDER BY id LIMIT 1) limited WHERE id=2",
        new long[0][]);
    assertRows(
        session,
        result,
        "SELECT category FROM "
            + "(SELECT DISTINCT category FROM events ORDER BY category DESC LIMIT 1) limited",
        new long[][] {{30}});
    assertRows(
        session,
        result,
        "SELECT category FROM (SELECT DISTINCT category FROM events LIMIT 0) limited",
        new long[0][]);
    assertRows(
        session,
        result,
        "SELECT category FROM "
            + "(SELECT category,COUNT(*) AS n FROM events GROUP BY category LIMIT 1) limited",
        new long[][] {{10}});
    assertRows(
        session,
        result,
        "SELECT category FROM "
            + "(SELECT category,COUNT(*) AS n FROM events GROUP BY category LIMIT 0) limited",
        new long[0][]);
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM events WHERE id<0) empty_count",
        new long[][] {{0}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(amount) AS n FROM events WHERE category=30) null_count",
        new long[][] {{0}});
    assertSingleNull(
        session,
        result,
        "SELECT total FROM (SELECT SUM(amount) AS total FROM events WHERE id<0) empty_sum");
    assertSingleNull(
        session,
        result,
        "SELECT minimum FROM (SELECT MIN(amount) AS minimum FROM events WHERE id<0) empty_min");
    assertSingleNull(
        session,
        result,
        "SELECT maximum FROM (SELECT MAX(amount) AS maximum FROM events WHERE id<0) empty_max");
    assertRows(
        session,
        result,
        "SELECT category,SUM(total) AS twice FROM "
            + "(SELECT category,SUM(amount) AS total FROM events GROUP BY category) grouped "
            + "GROUP BY category ORDER BY category",
        new long[][] {{10, 300}, {20, 300}, {30, 0}});
    assertRows(
        session,
        result,
        "SELECT total FROM (SELECT SUM(amount) AS total FROM events "
            + "HAVING SUM(amount)<0) scalar",
        new long[0][]);
    assertRows(
        session,
        result,
        "SELECT total FROM (SELECT SUM(amount) AS total FROM events) scalar "
            + "WHERE total=600",
        new long[][] {{600}});
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT total FROM (SELECT SUM(amount) AS total FROM events "
                + "HAVING SUM(amount)<0) scalar",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT total FROM (SELECT SUM(amount) AS total FROM events) scalar "
                + "WHERE total=600",
            result));
    assertEquals(600, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT total FROM (SELECT SUM(amount) AS total FROM events) scalar "
                + "WHERE total=600",
            result));
    assertEquals(600, result.valueAt(0));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT total FROM (SELECT SUM(amount) AS total FROM events) scalar "
                + "WHERE total=601",
            result));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "SELECT total FROM (SELECT category,SUM(amount) AS total FROM events "
                + "GROUP BY category) grouped WHERE total=300",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT category FROM (SELECT category,SUM(amount) AS total FROM events "
                + "GROUP BY category) grouped ORDER BY category LIMIT 1",
            result));
    assertEquals(10, result.valueAt(0));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT category FROM (SELECT category,SUM(amount) AS total FROM events "
                + "GROUP BY category) grouped LIMIT 0",
            result));

    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM events "
            + "WHERE EXTRACT(YEAR FROM day)=2024) computed_inner",
        new long[][] {{3}});
    assertRows(
        session,
        result,
        "SELECT category FROM (SELECT category,SUM(amount) AS total FROM events "
            + "GROUP BY category ORDER BY category DESC LIMIT 1) ordered_inner",
        new long[][] {{30}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT category,COUNT(*) AS n FROM events "
            + "GROUP BY category) grouped ORDER BY category",
        new long[][] {{2}, {1}, {1}});

    assertPlan(session, result, false);
    assertPlan(session, result, true);
    assertLimitPlan(session, result);
    assertDistinctPlan(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void ownsUnicodeTextAcrossGroupingDistinctSpillAndReuse(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = create(root);
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, label VARCHAR(20), day DATE)",
            result));
    for (int start = 1; start <= 1_025; start += 64) {
      int end = Math.min(1_025, start + 63);
      StringBuilder insert = new StringBuilder("INSERT INTO labels VALUES ");
      for (int id = start; id <= end; id++) {
        if (id > start) insert.append(',');
        String label = id % 3 == 0 ? "猫" : id % 3 == 1 ? HIGH_BMP : "😀";
        insert.append('(').append(id).append(",'").append(label)
            .append("',DATE '2024-01-01')");
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    }

    assertTextRows(
        session,
        result,
        "SELECT label FROM (SELECT DISTINCT label FROM labels) d ORDER BY label",
        new String[] {"猫", HIGH_BMP, "😀"});
    assertRowCount(
        session,
        result,
        "SELECT DISTINCT id FROM (SELECT DISTINCT id FROM labels) inner_distinct",
        1_025);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE empty_times (id BIGINT PRIMARY KEY, observed TIMESTAMP)",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginScan(
            "SELECT shifted FROM (SELECT MAX(observed AT TIME ZONE 'No/Such') AS shifted "
                + "FROM empty_times) zoned",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO empty_times VALUES "
                + "(1,TIMESTAMP '2024-03-31 01:30:00')",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginScan(
            "SELECT shifted FROM (SELECT MAX(observed AT TIME ZONE 'Europe/London') "
                + "AS shifted FROM empty_times) zoned",
            new SqlScanCursor()));
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM empty_times) recovered",
        new long[][] {{1}});
    assertTextRows(
        session,
        result,
        "SELECT label FROM (SELECT label,COUNT(*) AS n FROM labels "
            + "GROUP BY label HAVING COUNT(*)>300) g ORDER BY label",
        new String[] {"猫", HIGH_BMP, "😀"});
    assertTextRows(
        session,
        result,
        "SELECT minimum FROM (SELECT MIN(label) AS minimum FROM labels) extrema",
        new String[] {"猫"});
    assertTextRows(
        session,
        result,
        "SELECT maximum FROM (SELECT MAX(label) AS maximum FROM labels) extrema",
        new String[] {"😀"});
    assertRows(
        session,
        result,
        "SELECT bucket,n FROM (SELECT EXTRACT(YEAR FROM day) AS bucket, "
            + "COUNT(*) AS n FROM labels GROUP BY EXTRACT(YEAR FROM day)) years",
        new long[][] {{2024, 1_025}});
    assertTextRows(
        session,
        result,
        "SELECT label FROM (SELECT DISTINCT label FROM labels) d ORDER BY label",
        new String[] {"猫", HIGH_BMP, "😀"});

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void persistsUnicodeAggregateViewsWithoutRegressingProjectionViews(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = create(root);
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW totals AS SELECT category,SUM(amount) AS total "
                + "FROM events GROUP BY category HAVING COUNT(*)>0",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW unicode_labels AS SELECT label,COUNT(*) AS n "
                + "FROM events WHERE label>='猫' GROUP BY label",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW grand_total AS SELECT SUM(amount) AS total FROM events",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW event_count AS SELECT COUNT(*) AS n FROM events",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW distinct_labels AS SELECT DISTINCT label FROM events",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW derived_totals AS SELECT category,total FROM "
                + "(SELECT category,SUM(amount) AS total FROM events "
                + "GROUP BY category) grouped",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE VIEW plain AS SELECT id,category FROM events", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id,category FROM plain WHERE id=1", result));
    assertEquals(1, result.valueAt(0));
    assertEquals(10, result.valueAt(1));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT id FROM plain WHERE id=99", result));
    assertRows(
        session,
        result,
        "SELECT category,total FROM totals ORDER BY category",
        new long[][] {{10, 300}, {20, 300}, {30, 0}});
    assertRows(
        session,
        result,
        "SELECT total FROM grand_total",
        new long[][] {{600}});
    assertRows(session, result, "SELECT n FROM event_count", new long[][] {{4}});
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    opened.reset();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    session = openSession(database);
    assertRows(
        session,
        result,
        "SELECT category,total FROM totals ORDER BY category",
        new long[][] {{10, 300}, {20, 300}, {30, 0}});
    assertRows(session, result, "SELECT n FROM event_count", new long[][] {{4}});
    assertTextRows(
        session,
        result,
        "SELECT label FROM unicode_labels ORDER BY label",
        new String[] {"猫", "😀"});
    assertTextRows(
        session,
        result,
        "SELECT label FROM distinct_labels WHERE label IS NOT NULL ORDER BY label",
        new String[] {"猫", "😀"});
    assertRows(
        session,
        result,
        "SELECT category,total FROM derived_totals ORDER BY category",
        new long[][] {{10, 300}, {20, 300}, {30, 0}});
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id,category FROM plain WHERE id=1", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertPlan(
      SqlSession session, SqlExecutionResult result, boolean analyze) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    String prefix = analyze ? "EXPLAIN ANALYZE " : "EXPLAIN ";
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            prefix + "SELECT category,total FROM "
                + "(SELECT category,SUM(amount) AS total FROM events "
                + "GROUP BY category HAVING COUNT(*)>0) grouped "
                + "ORDER BY category",
            cursor));
    assertPlanRow(session, cursor, row, "block", 1, analyze ? 3 : -1);
    assertPlanRow(session, cursor, row, "sort", 1, -1);
    assertPlanRow(session, cursor, row, "block", 2, analyze ? 3 : -1);
    assertPlanRow(session, cursor, row, "having", 1, -1);
    assertPlanRow(session, cursor, row, "group", 2, -1);
    assertPlanRow(session, cursor, row, "sort", 0, -1);
    assertPlanRow(session, cursor, row, "table", -1, -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertLimitPlan(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN ANALYZE SELECT category FROM "
                + "(SELECT category,SUM(amount) AS total FROM events GROUP BY category) grouped "
                + "WHERE category<30 ORDER BY category LIMIT 1",
            cursor));
    assertPlanRow(session, cursor, row, "block", 1, 1);
    assertPlanRow(session, cursor, row, "limit", 1, -1);
    assertPlanRow(session, cursor, row, "sort", 1, -1);
    assertPlanRow(session, cursor, row, "filter", 1, -1);
    assertPlanRow(session, cursor, row, "block", 2, 3);
    assertPlanRow(session, cursor, row, "group", 1, -1);
    assertPlanRow(session, cursor, row, "sort", 0, -1);
    assertPlanRow(session, cursor, row, "table", -1, -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertDistinctPlan(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT DISTINCT label FROM (SELECT label FROM events) projected "
                + "ORDER BY label",
            cursor));
    assertPlanRow(session, cursor, row, "block", 1, -1);
    assertPlanRow(session, cursor, row, "dedupe", 1, -1);
    assertPlanRow(session, cursor, row, "sort", 1, -1);
    assertPlanRow(session, cursor, row, "block", 2, -1);
    assertPlanRow(session, cursor, row, "table", -1, -1);
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

  private static void createFixture(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events (id BIGINT PRIMARY KEY, category BIGINT, "
                + "amount BIGINT, label VARCHAR(20), day DATE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1,10,100,'猫',DATE '2024-01-01'),"
                + "(2,10,200,'😀',DATE '2024-01-02'),"
                + "(3,20,300,'猫',DATE '2024-01-03'),"
                + "(4,30,NULL,NULL,NULL)",
            result));
  }

  private static RelationalDatabaseOpenResult create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    return opened;
  }

  private static SqlSession openSession(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
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

  private static void assertTextRows(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      String[] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    char[] text = new char[510];
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (String value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      int length = row.copyTextAt(0, text, 0);
      assertTrue(length >= 0);
      assertEquals(value, new String(text, 0, length));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertRowCount(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      int expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    int count = 0;
    StatusCode terminal;
    while ((terminal = session.nextScan(cursor, row)).isOk()) count++;
    assertEquals(expected, count);
    assertEquals(StatusCode.CONFLICT, terminal);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertSingleNull(
      SqlSession session, SqlExecutionResult result, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertTrue(row.isNull(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
