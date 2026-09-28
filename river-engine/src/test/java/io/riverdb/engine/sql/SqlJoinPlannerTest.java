package io.riverdb.engine.sql;

import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
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

/** Durable ANALYZE, deterministic SQL-order costing, and estimate-plan evidence. */
final class SqlJoinPlannerTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a4f494e504c414eL, 0x4e4552434f535431L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void costsBoundedStrategiesFromDurableStatistics(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);

    String small = "SELECT a.id,b.id FROM cost_left a "
        + "JOIN cost_small b ON a.id=b.left_id";
    String large = "SELECT a.id,b.id FROM cost_left a "
        + "JOIN cost_large b ON a.id=b.left_id";
    assertPlanContains(session, result, "EXPLAIN " + small, "merge");
    assertEquals(StatusCode.CONFLICT, session.execute("ANALYZE missing", result));
    analyze(session, result, "cost_left", 4);
    analyze(session, result, "cost_small", 1);
    assertPlanContains(session, result, "EXPLAIN " + small, "join");
    assertPlanDoesNotContain(session, result, "EXPLAIN " + small, "merge");
    assertEstimatedPlan(session, result, small, 4, 1, 1, "join");

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    analyze(session, result, "cost_large", 4);
    assertEstimatedPlan(session, result, large, 4, 4, 4, "hash");
    assertRows(session, result, large, 4);
    assertSampledStatistics(session, result);
    String grouped = "SELECT aid,COUNT(*) FROM (SELECT a.id AS aid,b.id AS bid "
        + "FROM cost_left a JOIN cost_large b ON a.id=b.left_id) pairs "
        + "GROUP BY aid ORDER BY aid";
    assertPlanContains(session, result, "EXPLAIN ANALYZE " + grouped, "exact");
    assertPlanContains(session, result, "EXPLAIN ANALYZE " + grouped, "est");
    assertPlanContains(session, result, "EXPLAIN ANALYZE " + grouped, "join");
    assertRows(session, result, grouped, 4);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    opened.reset();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8,
            EmbeddedLockDiagnosticsConfig.disabled(), opened));
    database = opened.database();
    session = openSession(database);
    assertEstimatedPlan(session, result, small, 4, 1, 1, "join");
    assertEstimatedPlan(session, result, large, 4, 4, 4, "hash");

    assertEquals(StatusCode.OK, session.execute("DROP TABLE cost_small", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE cost_small (id BIGINT PRIMARY KEY,left_id BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO cost_small VALUES (21,1)", result));
    assertPlanDoesNotContain(session, result, "EXPLAIN " + small, "exact");
    assertRows(session, result, small, 1);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void reordersQualifiedInnerAggregateWhenAnalyzedRootIsSmaller(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);
    String query = "SELECT COUNT(DISTINCT a.id) FROM cost_left a "
        + "JOIN cost_small b ON a.id=b.left_id";
    assertRootRows(session, result, query, 4);
    analyze(session, result, "cost_left", 4);
    analyze(session, result, "cost_small", 1);
    assertRootRows(session, result, query, 1);
    assertCount(session, result, query, 1);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void selectsRootFromAnalyzedRangeSelectivity(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);
    analyze(session, result, "cost_left", 4);
    analyze(session, result, "cost_large", 4);
    String source = "SELECT COUNT(DISTINCT a.id) FROM cost_left a "
        + "JOIN cost_large b ON a.id=b.left_id WHERE ";
    assertRootRows(session, result, source + "a.id<=4 AND b.id>=14", 1);
    assertRootRows(session, result, source + "a.id<=1 AND b.id>=11", 1);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void unindexedZeroMatchFilterStillPaysForRootScan(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE cost_scan (id BIGINT PRIMARY KEY,qty INTEGER)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE cost_probe (id BIGINT PRIMARY KEY,scan_id BIGINT)", result));
    for (int first = 1; first <= 100; first += 50) {
      StringBuilder insert = new StringBuilder("INSERT INTO cost_scan VALUES ");
      for (int id = first; id < first + 50; id++) {
        if (id > first) insert.append(',');
        insert.append('(').append(id).append(",10)");
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    }
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO cost_probe VALUES (11,1),(12,2),(13,3),(14,4)", result));
    String query = "SELECT COUNT(DISTINCT s.id) FROM cost_scan s "
        + "JOIN cost_probe p ON s.id=p.scan_id "
        + "WHERE s.qty<10 AND p.id>=14";
    analyze(session, result, "cost_scan", 100);
    analyze(session, result, "cost_probe", 4);
    assertRootRows(session, result, query, 1);
    assertRootRows(session, result,
        "SELECT COUNT(DISTINCT s.id) FROM cost_scan s "
            + "JOIN cost_probe p ON s.id=p.scan_id "
            + "WHERE s.id>=1 AND s.qty<10", 4);
    assertCount(session, result, query, 0);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void costsConstrainedCompoundPrimaryKeyPrefix(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE cost_stock (id BIGINT PRIMARY KEY)", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE cost_composite (w BIGINT,d BIGINT,id BIGINT,item BIGINT,"
            + "PRIMARY KEY(w,d,id))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO cost_stock VALUES (9),(10)", result));
    for (int first = 1; first <= 100; first += 50) {
      StringBuilder insert = new StringBuilder("INSERT INTO cost_composite VALUES ");
      for (int row = first; row < first + 50; row++) {
        if (row > first) insert.append(',');
        int district = (row - 1) / 10 + 1;
        int id = (row - 1) % 10 + 1;
        insert.append("(1,").append(district).append(',').append(id).append(',')
            .append(id % 2 == 0 ? 10 : 9).append(')');
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    }
    analyze(session, result, "cost_stock", 2);
    analyze(session, result, "cost_composite", 100);
    String query = "SELECT COUNT(DISTINCT s.id) FROM cost_stock s "
        + "JOIN cost_composite c ON s.id=c.item "
        + "WHERE c.w=1 AND c.d=1 AND c.id>=9 AND c.id<11";
    assertRootRows(session, result, query, 2);
    assertCount(session, result, query, 2);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRootRows(
      SqlSession session, SqlExecutionResult result, String query, long expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN ANALYZE " + query, cursor));
    boolean found = false;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      if (!found && (row.valueAt(0) == PackedText.pack("primary")
          || row.valueAt(0) == PackedText.pack("table"))) {
        assertEquals(expected, row.valueAt(2));
        found = true;
      }
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertTrue(found);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertCount(
      SqlSession session, SqlExecutionResult result, String query, long expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(query, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(expected, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void createFixture(SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE cost_left (id BIGINT PRIMARY KEY,label VARCHAR(16))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE cost_small (id BIGINT PRIMARY KEY,left_id BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE cost_large (id BIGINT PRIMARY KEY,left_id BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO cost_left VALUES "
                + "(1,'one'),(2,'two'),(3,'three'),(4,'four')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO cost_small VALUES (11,1)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO cost_large VALUES (11,1),(12,2),(13,3),(14,4)",
            result));
  }

  private static void analyze(
      SqlSession session, SqlExecutionResult result, String table, int rows) {
    assertEquals(StatusCode.OK, session.execute("ANALYZE TABLE " + table, result));
    assertEquals(rows, result.affectedRows());
    assertTrue(result.commitSequence() > 0);
  }

  private static void assertSampledStatistics(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE cost_sample (id BIGINT PRIMARY KEY,padding BIGINT)", result));
    for (int first = 1; first <= 1_025; first += 64) {
      int last = Math.min(1_025, first + 63);
      StringBuilder insert = new StringBuilder("INSERT INTO cost_sample VALUES ");
      for (int id = first; id <= last; id++) {
        if (id > first) insert.append(',');
        insert.append('(').append(id).append(',').append(id).append(')');
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    }
    analyze(session, result, "cost_sample", 1_025);
    assertPlanContains(
        session,
        result,
        "EXPLAIN SELECT a.id,b.id FROM cost_sample a "
            + "JOIN cost_sample b ON a.id=b.id",
        "sample");
  }

  private static void assertEstimatedPlan(
      SqlSession session,
      SqlExecutionResult result,
      String query,
      long leftRows,
      long rightRows,
      long estimatedRows,
      String strategy) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN ANALYZE " + query, cursor));
    assertPlanRow(session, cursor, row, "table", -1, leftRows);
    assertMetadataRow(session, cursor, row, "exact", leftRows);
    assertPlanRow(session, cursor, row, "table", 1, leftRows);
    assertPlanRow(session, cursor, row, "on", 1, estimatedRows);
    assertPlanRow(session, cursor, row, strategy, 1, estimatedRows);
    assertMetadataRow(session, cursor, row, "exact", rightRows);
    assertPlanRow(session, cursor, row, "est", 1, estimatedRows);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertMetadataRow(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      String operator,
      long rows) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack(operator), row.valueAt(0));
    assertTrue(row.valueAt(1) >= 0);
    assertEquals(rows, row.valueAt(2));
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
    assertEquals(rows, row.valueAt(2));
  }

  private static void assertPlanContains(
      SqlSession session,
      SqlExecutionResult result,
      String query,
      String operator) {
    assertPlanPresence(session, result, query, operator, true);
  }

  private static void assertPlanDoesNotContain(
      SqlSession session,
      SqlExecutionResult result,
      String query,
      String operator) {
    assertPlanPresence(session, result, query, operator, false);
  }

  private static void assertPlanPresence(
      SqlSession session,
      SqlExecutionResult result,
      String query,
      String operator,
      boolean expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(query, cursor));
    boolean found = false;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)).isOk()) {
      found |= row.valueAt(0) == PackedText.pack(operator);
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expected, found);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertRows(
      SqlSession session, SqlExecutionResult result, String query, int expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(query, cursor));
    int rows = 0;
    while (session.nextScan(cursor, row).isOk()) rows++;
    assertEquals(expected, rows);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static SqlSession openSession(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
