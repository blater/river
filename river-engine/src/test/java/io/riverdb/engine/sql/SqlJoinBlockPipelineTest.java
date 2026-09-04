package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Real-path evidence for a deepest JOIN materialized into parent block stages. */
final class SqlJoinBlockPipelineTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a4f494e424c4f43L, 0x4b50344232303031L);
  private static final String HIGH_BMP = Character.toString(0xe000);

  @Test
  void feedsProjectionDistinctGroupHavingOrderAndTruthfulPlan(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = create(root);
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);

    assertRows(
        session,
        result,
        "SELECT lid,rid FROM (SELECT l.id AS lid,r.id AS rid "
            + "FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined ORDER BY lid",
        new long[][] {{1, 11}, {2, 21}, {3, 31}});
    assertTextRows(
        session,
        result,
        "SELECT DISTINCT label FROM (SELECT r.label AS label "
            + "FROM left_rows l JOIN right_rows r ON l.id=r.left_id "
            + "WHERE r.flag=TRUE) joined ORDER BY label",
        new String[] {"fox", "猫", "😀"});
    assertRows(
        session,
        result,
        "SELECT bucket,SUM(amount+1) AS n FROM (SELECT l.bucket AS bucket,"
            + "r.amount AS amount "
            + "FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined "
            + "GROUP BY bucket HAVING SUM(amount+1)>301 ORDER BY bucket",
        new long[][] {{10, 302}});
    assertScalarCounts(session, result);
    assertTextRows(
        session,
        result,
        "SELECT DISTINCT rendered FROM (SELECT CAST(l.observed AS VARCHAR(32)) "
            + "AS rendered FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined ORDER BY rendered",
        new String[] {
            "2024-03-31 00:30:00.000000",
            "2024-03-31 01:30:00.000000",
            "2024-04-01 00:30:00.000000"
        });
    assertLeftComputedNullability(session, result);
    assertJoinPlan(session, result, true, true);
    assertJoinPlan(session, result, false, true);
    assertJoinPlan(session, result, true, false);
    assertTemporalAtomicFailure(session, result);
    assertRows(
        session,
        result,
        "SELECT lid,COUNT(*) AS n FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid DESC LIMIT 1) "
            + "joined GROUP BY lid",
        new long[][] {{3, 1}});
    assertRows(
        session,
        result,
        "SELECT lid FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid DESC LIMIT 1) "
            + "joined",
        new long[][] {{3}});
    assertRows(
        session,
        result,
        "SELECT bucket,amount FROM (SELECT l.bucket AS bucket,r.amount AS amount "
            + "FROM left_rows l JOIN right_rows r ON l.id=r.left_id "
            + "ORDER BY bucket ASC,amount DESC LIMIT 1) joined",
        new long[][] {{10, 200}});
    assertRows(
        session,
        result,
        "SELECT bucket,amount FROM (SELECT l.bucket AS bucket,r.amount AS amount "
            + "FROM left_rows l JOIN right_rows r ON l.id=r.left_id) joined "
            + "ORDER BY bucket ASC,amount DESC LIMIT 1",
        new long[][] {{10, 200}});
    assertRows(
        session,
        result,
        "SELECT l.id AS lid,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.id "
            + "ORDER BY n DESC,lid DESC LIMIT 2",
        new long[][] {{1, 2}, {3, 1}});
    assertRows(
        session,
        result,
        "SELECT COUNT(*) AS n FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid DESC LIMIT 0) "
            + "joined",
        new long[][] {{0}});
    assertRows(
        session,
        result,
        "SELECT l.bucket AS bucket,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.bucket "
            + "ORDER BY n DESC LIMIT 1",
        new long[][] {{10, 3}});
    assertRows(
        session,
        result,
        "SELECT l.bucket AS bucket,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.bucket "
            + "ORDER BY n DESC",
        new long[][] {{10, 3}, {20, 1}});
    assertRows(
        session,
        result,
        "SELECT l.bucket AS bucket,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.bucket LIMIT 0",
        new long[0][]);
    assertRows(
        session,
        result,
        "SELECT COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id LIMIT 0",
        new long[0][]);
    assertDeepestOnly(session, result);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW direct_join AS SELECT l.id AS lid FROM left_rows l "
                + "JOIN right_rows r ON l.id=r.left_id",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW derived_join AS SELECT lid FROM "
                + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
                + "ON l.id=r.left_id) joined",
            result));
    assertRows(
        session,
        result,
        "SELECT lid FROM direct_join ORDER BY lid",
        new long[][] {{1}, {1}, {2}, {3}});
    assertRows(
        session,
        result,
        "SELECT lid FROM derived_join ORDER BY lid",
        new long[][] {{1}, {1}, {2}, {3}});
    assertEquals(StatusCode.OK, session.execute("DROP VIEW direct_join", result));
    assertEquals(StatusCode.OK, session.execute("DROP VIEW derived_join", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM left_rows WHERE id=1", result));
    assertEquals(10, result.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void spillsOwnedUnicodeJoinRowsIntoGroupedAndDistinctParents(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = create(root);
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_left "
                + "(id BIGINT PRIMARY KEY,bucket BIGINT,label VARCHAR(20))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_right "
                + "(id BIGINT PRIMARY KEY,label VARCHAR(20))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_third (id BIGINT PRIMARY KEY,marker BIGINT)",
            result));
    for (int start = 1; start <= 1_025; start += 64) {
      int end = Math.min(1_025, start + 63);
      StringBuilder left = new StringBuilder("INSERT INTO spill_left VALUES ");
      StringBuilder right = new StringBuilder("INSERT INTO spill_right VALUES ");
      StringBuilder third = new StringBuilder("INSERT INTO spill_third VALUES ");
      for (int id = start; id <= end; id++) {
        if (id > start) {
          left.append(',');
          right.append(',');
          third.append(',');
        }
        String label = id % 3 == 0 ? "猫" : id % 3 == 1 ? HIGH_BMP : "😀";
        left.append('(').append(id).append(',').append(id % 2)
            .append(",'left')");
        third.append('(').append(id).append(',').append(id + 1).append(')');
        right.append('(').append(id).append(",'").append(label).append("')");
      }
      assertEquals(StatusCode.OK, session.execute(left.toString(), result));
      assertEquals(StatusCode.OK, session.execute(right.toString(), result));
      assertEquals(StatusCode.OK, session.execute(third.toString(), result));
    }
    assertDirectThreeRoleSpill(session, result);
    assertRows(
        session,
        result,
        "SELECT bucket,lid FROM (SELECT l.bucket AS bucket,l.id AS lid "
            + "FROM spill_left l JOIN spill_right r ON l.id=r.id "
            + "JOIN spill_third t ON r.id=t.id "
            + "ORDER BY bucket ASC,lid DESC LIMIT 1) joined",
        new long[][] {{0, 1_024}});
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW spill_join AS SELECT l.id AS lid,l.bucket AS bucket,"
                + "r.label AS label FROM spill_left l JOIN spill_right r "
                + "ON l.id=r.id JOIN spill_third t ON r.id=t.id",
            result));

    assertRows(
        session,
        result,
        "SELECT bucket,COUNT(*) AS n FROM spill_join "
            + "GROUP BY bucket HAVING COUNT(*)>500 ORDER BY bucket",
        new long[][] {{0, 512}, {1, 513}});
    assertTextRows(
        session,
        result,
        "SELECT DISTINCT label FROM spill_join ORDER BY label",
        new String[] {"猫", HIGH_BMP, "😀"});
    assertRowCount(
        session,
        result,
        "SELECT lid FROM spill_join",
        1_025);
    assertEquals(StatusCode.OK, session.execute("DROP VIEW spill_join", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertDirectThreeRoleSpill(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    char[] text = new char[4];
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT l.id AS lid,r.label AS label FROM spill_left l "
                + "JOIN spill_right r ON l.id=r.id "
                + "JOIN spill_third t ON r.id=t.id ORDER BY label",
            cursor));
    assertEquals(
        io.riverdb.base.type.SqlTypeDescriptor.TYPE_ID_VARCHAR,
        io.riverdb.base.type.SqlTypeDescriptor.typeId(
            session.scanColumnTypeDescriptor(cursor, 1)));
    for (int index = 0; index < 1_025; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      String expected = index < 341 ? "猫" : index < 683 ? HIGH_BMP : "😀";
      assertFalse(row.isNull(1), "NULL text at row " + index + " id " + row.valueAt(0));
      int length = row.copyTextAt(1, text, 0);
      assertTrue(length >= 0, "missing text at sorted row " + index);
      assertEquals(expected, new String(text, 0, length));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM spill_left WHERE id=1", result));
    assertEquals(1, result.valueAt(0));
  }

  private static void createFixture(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE left_rows (id BIGINT PRIMARY KEY,bucket BIGINT,"
                + "label VARCHAR(20),observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX left_bucket ON left_rows(bucket)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO left_rows VALUES "
                + "(1,10,'猫',TIMESTAMP '2024-03-31 00:30:00'),"
                + "(2,10,'😀',TIMESTAMP '2024-03-31 01:30:00'),"
                + "(3,20,'fox',TIMESTAMP '2024-04-01 00:30:00'),"
                + "(4,30,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE right_rows (id BIGINT PRIMARY KEY,left_id BIGINT,"
                + "amount BIGINT,label VARCHAR(20),flag BOOLEAN)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX right_left_id ON right_rows(left_id)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO right_rows VALUES "
                + "(11,1,100,'猫',TRUE),(12,1,101,'ignored',FALSE),"
                + "(21,2,200,'😀',TRUE),(31,3,300,'fox',TRUE)",
            result));
  }

  private static void assertLeftComputedNullability(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT lid,adjusted FROM (SELECT l.id AS lid,r.amount+1 AS adjusted "
                + "FROM left_rows l LEFT JOIN right_rows r "
                + "ON l.id=r.left_id AND r.flag=TRUE) joined ORDER BY lid",
            cursor));
    assertFalse(session.scanColumnIsNullable(cursor, 0));
    assertTrue(session.scanColumnIsNullable(cursor, 1));
    for (long[] expected : new long[][] {{1, 101}, {2, 201}, {3, 301}}) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(expected[0], row.valueAt(0));
      assertEquals(expected[1], row.valueAt(1));
    }
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(4, row.valueAt(0));
    assertTrue(row.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertRows(
        session,
        result,
        "SELECT lid,rid FROM (SELECT l.id AS lid,r.id AS rid "
            + "FROM left_rows l LEFT JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE "
            + "WHERE r.amount>150 OR r.id IS NULL) joined ORDER BY lid",
        new long[][] {{2, 21}, {3, 31}, {4, 0}});
  }

  private static void assertScalarCounts(
      SqlSession session, SqlExecutionResult result) {
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined) counted",
        new long[][] {{3}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id WHERE l.id<0) joined) counted",
        new long[][] {{0}});
    assertRows(
        session,
        result,
        "SELECT SUM(amount+1) AS total FROM (SELECT r.amount AS amount FROM "
            + "left_rows l JOIN right_rows r ON l.id=r.left_id "
            + "WHERE r.flag=TRUE) joined",
        new long[][] {{603}});
    assertRows(
        session,
        result,
        "SELECT COUNT(*) AS n FROM (SELECT r.amount AS amount FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id) joined WHERE amount>150",
        new long[][] {{2}});
    assertRows(
        session,
        result,
        "SELECT COUNT(n) AS n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id) joined) counted",
        new long[][] {{1}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id) joined) counted WHERE n<0",
        new long[0][]);
    assertSingleNull(
        session,
        result,
        "SELECT total FROM (SELECT SUM(amount+1) AS total FROM "
            + "(SELECT r.amount AS amount FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id WHERE l.id<0) joined) totals");
  }

  private static void assertJoinPlan(
      SqlSession session,
      SqlExecutionResult result,
      boolean edge,
      boolean analyze) {
    String on = edge
        ? "l.id=r.left_id AND r.flag=TRUE" : "l.id+10=r.id";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            (analyze ? "EXPLAIN ANALYZE " : "EXPLAIN ")
                + "SELECT lid FROM (SELECT l.id AS lid "
                + "FROM left_rows l JOIN right_rows r ON " + on
                + (edge ? " WHERE l.bucket=10" : "") + ") joined",
            cursor));
    assertPlanRow(session, cursor, row, "block", 1, analyze ? 2 : -1);
    assertPlanRow(session, cursor, row, "block", 2, analyze ? 2 : -1);
    assertPlanRow(
        session, cursor, row, edge ? "index" : "table", edge ? 1 : -1,
        analyze ? edge ? 2 : 4 : -1);
    assertPlanRow(
        session, cursor, row, edge ? "index" : "table", edge ? 1 : -1,
        analyze ? edge ? 3 : 16 : -1);
    assertPlanRow(
        session, cursor, row, "on", edge ? 2 : 1,
        analyze ? 2 : -1);
    assertPlanRow(
        session, cursor, row, "join", edge ? 2 : 1,
        analyze ? 2 : -1);
    if (edge) assertPlanRow(session, cursor, row, "filter", 1, analyze ? 2 : -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertTemporalAtomicFailure(
      SqlSession session, SqlExecutionResult result) {
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

  private static void assertDeepestOnly(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginScan(
            "SELECT j.lid FROM (SELECT l.id AS lid FROM left_rows l) j "
                + "JOIN right_rows r ON j.lid=r.left_id",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginScan(
            "SELECT j.lid FROM (SELECT l.id AS lid FROM left_rows l "
                + "JOIN right_rows r ON l.id=r.left_id) j "
                + "JOIN right_rows x ON j.lid=x.left_id",
            new SqlScanCursor()));
    assertRows(
        session,
        result,
        "SELECT lid FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid) joined",
        new long[][] {{1}, {1}, {2}, {3}});
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM left_rows WHERE id=3", result));
    assertEquals(20, result.valueAt(0));
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
      assertEquals(0, row.key());
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
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) count++;
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expected, count);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static RelationalDatabaseOpenResult create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            databaseRequest(8),
            root, DATABASE, WalGeneration.of(1), 8, opened));
    return opened;
  }

  private static SqlSession openSession(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
