package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
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
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
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
    assertEquals("operator", session.scanColumnName(cursor, 0).toString());
    assertEquals("detail", session.scanColumnName(cursor, 1).toString());
    assertEquals("rows", session.scanColumnName(cursor, 2).toString());
    assertEquals(SqlTypeDescriptor.varchar(64), session.scanColumnTypeDescriptor(cursor, 0));
    assertEquals(SqlTypeDescriptor.BIGINT, session.scanColumnTypeDescriptor(cursor, 1));
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

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, category BIGINT, code BIGINT)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO labels VALUES (1,7,70),(2,7,71),(3,8,80)",
            execution));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT events.id, labels.code FROM events "
                + "JOIN labels ON events.category=labels.category",
            cursor));
    assertPlanRow(session, cursor, row, "index", 1);
    assertPlanRow(session, cursor, row, "sort", 1);
    assertPlanRow(session, cursor, row, "on", 1);
    assertPlanRow(session, cursor, row, "merge", 1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN ANALYZE SELECT events.id, labels.code FROM events "
                + "JOIN labels ON events.category=labels.category",
            cursor));
    assertPlanRow(session, cursor, row, "index", 1);
    assertEquals(3, row.valueAt(2));
    assertPlanRow(session, cursor, row, "sort", 1);
    assertEquals(5, row.valueAt(2));
    assertPlanRow(session, cursor, row, "on", 1);
    assertEquals(5, row.valueAt(2));
    assertPlanRow(session, cursor, row, "merge", 1);
    assertEquals(5, row.valueAt(2));
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

  @Test
  void explainAndExecutionShareCompleteFamilyBinding(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, label VARCHAR(16))",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE labels "
                + "(id BIGINT PRIMARY KEY, category BIGINT, label VARCHAR(16))",
            execution));

    String[] invalid = {
      "SELECT wrong.category, COUNT(*) FROM events GROUP BY category",
      "SELECT category, SUM(missing) FROM events GROUP BY category",
      "SELECT DISTINCT wrong.category FROM events",
      "SELECT events.id, labels.id FROM events "
          + "JOIN labels ON events.missing=labels.category"
    };
    for (String sql : invalid) {
      assertEquals(
          StatusCode.INVALID_EXTERNAL_INPUT,
          session.beginScan(sql, new SqlScanCursor()),
          sql);
      assertEquals(
          StatusCode.INVALID_EXTERNAL_INPUT,
          session.beginScan("EXPLAIN " + sql, new SqlScanCursor()),
          sql);
    }
    String[] mismatched = {
      "SELECT category, SUM(label) FROM events GROUP BY category",
      "SELECT events.id, labels.id FROM events "
          + "JOIN labels ON events.category=labels.label"
    };
    for (String sql : mismatched) {
      assertEquals(
          StatusCode.DATATYPE_MISMATCH,
          session.beginScan(sql, new SqlScanCursor()),
          sql);
      assertEquals(
          StatusCode.DATATYPE_MISMATCH,
          session.beginScan("EXPLAIN " + sql, new SqlScanCursor()),
          sql);
    }
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
