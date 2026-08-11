package io.riverdb.engine.sql;

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

final class SqlExplainTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4558504c41494e31L, 0x504c414e54455354L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void explainsAndAnalyzesThePhysicalScanSelectedByExecution(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX events_category ON events(category)", execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES (1,7,30),(2,7,10),(3,8,20)", execution));

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT id, amount FROM events WHERE category=7",
            cursor));
    assertEquals("operator", session.scanColumnName(cursor, 0));
    assertEquals("detail", session.scanColumnName(cursor, 1));
    assertEquals("rows", session.scanColumnName(cursor, 2));
    assertTrue(session.scanColumnIsVarchar(cursor, 0));
    assertFalse(session.scanColumnIsVarchar(cursor, 1));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("filter"), row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertTrue(row.isNull(2));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("index"), row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertTrue(row.isNull(2));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN ANALYZE SELECT id, amount FROM events WHERE category=7",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("filter"), row.valueAt(0));
    assertFalse(row.isNull(2));
    assertEquals(2, row.valueAt(2));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("index"), row.valueAt(0));
    assertTrue(row.isNull(2));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT id, amount FROM events ORDER BY amount DESC LIMIT 2",
            cursor));
    assertPlanRow(session, cursor, row, "limit", 2);
    assertPlanRow(session, cursor, row, "sort", -1);
    assertPlanRow(session, cursor, row, "table", -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT COUNT(*) FROM events WHERE category=7", cursor));
    assertPlanRow(session, cursor, row, "agg", -1);
    assertPlanRow(session, cursor, row, "filter", 1);
    assertPlanRow(session, cursor, row, "index", 1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT category, SUM(amount) FROM events GROUP BY category",
            cursor));
    assertPlanRow(session, cursor, row, "group", 2);
    assertPlanRow(session, cursor, row, "sort", 1);
    assertPlanRow(session, cursor, row, "table", -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT d.id FROM "
                + "(SELECT id, amount FROM events) d WHERE d.amount=10",
            cursor));
    assertPlanRow(session, cursor, row, "nested", 2);
    assertPlanRow(session, cursor, row, "filter", 1);
    assertPlanRow(session, cursor, row, "table", -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN ANALYZE SELECT id, amount FROM events "
                + "ORDER BY amount DESC LIMIT 2",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("limit"), row.valueAt(0));
    assertEquals(2, row.valueAt(1));
    assertEquals(2, row.valueAt(2));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertPlanRow(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      String operator,
      long detail) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack(operator), row.valueAt(0));
    assertEquals(detail, row.valueAt(1));
  }
}
