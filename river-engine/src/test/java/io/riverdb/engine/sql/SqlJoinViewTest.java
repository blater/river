package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import io.riverdb.engine.relational.RelationalSessionOpenResult;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.ViewDefinition;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Durable two-table lineage and execution evidence for direct and staged JOIN views. */
final class SqlJoinViewTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a4f494e56494557L, 0x5034423330303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void persistsOrderedLineageAndExecutesDirectAndStagedViews(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = create(root);
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    createFixture(session, result);
    assertSelfJoinBoundary(session, result);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW direct_join AS SELECT l.id AS lid,r.amount+1 AS adjusted "
                + "FROM join_left l LEFT JOIN join_right r "
                + "ON l.id=r.left_id AND r.flag=TRUE",
            result));
    assertDirectRows(session, result);
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW grouped_join AS SELECT bucket,SUM(amount+1) AS total "
                + "FROM (SELECT l.bucket AS bucket,r.amount AS amount "
                + "FROM join_left l JOIN join_right r "
                + "ON l.id=r.left_id AND r.flag=TRUE) joined "
                + "GROUP BY bucket HAVING SUM(amount+1)>200",
            result));
    assertRows(
        session,
        result,
        "SELECT bucket,total FROM grouped_join ORDER BY bucket",
        new long[][] {{10, 302}});
    assertOrderedLineage(database, "direct_join");
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("DROP TABLE join_left", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("DROP TABLE join_right", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "ALTER TABLE join_right RENAME TO renamed_right", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "ALTER TABLE join_right RENAME COLUMN amount TO total", result));
    assertDirectPlan(session, result);
    assertGroupedPlan(session, result);
    assertTemporalBoundaries(session, result);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    opened.reset();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(
            root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    session = openSession(database);
    assertDirectRows(session, result);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT adjusted FROM direct_join WHERE lid=1", result));
    assertEquals(SqlTypeDescriptor.BIGINT, result.typeDescriptorAt(0));
    assertEquals(101, result.valueAt(0));
    assertRows(
        session,
        result,
        "SELECT bucket,total FROM grouped_join ORDER BY bucket",
        new long[][] {{10, 302}});
    assertEquals(StatusCode.OK, session.execute("DROP VIEW direct_join", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("DROP TABLE join_right", result));
    assertEquals(StatusCode.OK, session.execute("DROP VIEW grouped_join", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE join_right", result));
    assertEquals(StatusCode.OK, session.execute("DROP TABLE join_left", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFixture(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE join_left (id BIGINT PRIMARY KEY,bucket BIGINT,"
                + "observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO join_left VALUES "
                + "(1,10,TIMESTAMP '2024-03-31 00:30:00'),"
                + "(2,10,TIMESTAMP '2024-03-31 01:30:00'),"
                + "(3,20,TIMESTAMP '2024-04-01 00:30:00')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE join_right (id BIGINT PRIMARY KEY,left_id BIGINT,"
                + "amount BIGINT,flag BOOLEAN)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX join_right_left ON join_right(left_id)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO join_right VALUES "
                + "(11,1,100,TRUE),(12,1,101,FALSE),(21,2,200,TRUE)",
            result));
  }

  private static void assertSelfJoinBoundary(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT lid FROM (SELECT l.id AS lid FROM join_left l "
                + "JOIN join_left r ON l.id=r.id) joined ORDER BY lid",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(3, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE VIEW direct_self_join AS SELECT l.id AS lid "
                + "FROM join_left l JOIN join_left r ON l.id=r.id",
            result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE VIEW derived_self_join AS SELECT lid FROM "
                + "(SELECT l.id AS lid FROM join_left l "
                + "JOIN join_left r ON l.id=r.id) joined",
            result));
  }

  private static void assertOrderedLineage(
      RelationalDatabase database, String viewName) {
    RelationalSessionOpenResult opened = new RelationalSessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(opened));
    assertEquals(
        StatusCode.OK,
        opened.session().begin(IsolationLevel.REPEATABLE_READ));
    ViewDefinition view = new ViewDefinition();
    TableDefinition left = new TableDefinition();
    TableDefinition right = new TableDefinition();
    assertEquals(StatusCode.OK, opened.session().resolveTable("join_left", left));
    assertEquals(StatusCode.OK, opened.session().resolveTable("join_right", right));
    assertEquals(StatusCode.OK, opened.session().resolveView(viewName, view));
    assertEquals(2, view.tableCount());
    assertEquals(left.tableId(), view.baseTableId());
    assertEquals(right.tableId(), view.joinTableId());
    assertEquals(
        StatusCode.OK,
        opened.session().abort(new TransactionOutcome()));
  }

  private static void assertDirectRows(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT lid,adjusted FROM direct_join ORDER BY lid", cursor));
    assertFalse(session.scanColumnIsNullable(cursor, 0));
    assertEquals(true, session.scanColumnIsNullable(cursor, 1));
    for (long[] expected : new long[][] {{1, 101}, {2, 201}}) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(expected[0], row.valueAt(0));
      assertEquals(expected[1], row.valueAt(1));
      assertFalse(row.isNull(1));
    }
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(3, row.valueAt(0));
    assertEquals(true, row.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertTemporalBoundaries(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "CREATE VIEW bad_zone_join AS SELECT l.id AS lid "
                + "FROM join_left l JOIN join_right r "
                + "ON l.id=r.left_id AND "
                + "(l.observed AT TIME ZONE 'No/Such') IS NOT NULL",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW valid_zone_join AS SELECT l.id AS lid,"
                + "l.observed AT TIME ZONE 'Europe/London' AS shifted "
                + "FROM join_left l JOIN join_right r "
                + "ON l.id=r.left_id AND r.flag=TRUE "
                + "WHERE l.id=1",
            result));
    assertRows(
        session,
        result,
        "SELECT lid FROM valid_zone_join",
        new long[][] {{1}});
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW gap_zone_join AS SELECT l.id AS lid,"
                + "l.observed AT TIME ZONE 'Europe/London' AS shifted "
                + "FROM join_left l JOIN join_right r "
                + "ON l.id=r.left_id AND r.flag=TRUE",
            result));
    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginScan("SELECT lid,shifted FROM gap_zone_join", cursor));
    assertFalse(cursor.isActive());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM join_left WHERE id=1", result));
    assertEquals(10, result.valueAt(0));
    assertEquals(StatusCode.OK, session.execute("DROP VIEW gap_zone_join", result));
    assertEquals(StatusCode.OK, session.execute("DROP VIEW valid_zone_join", result));
  }

  private static void assertDirectPlan(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan("EXPLAIN SELECT lid FROM direct_join", cursor));
    assertPlanRow(session, cursor, row, "block", 1, -1);
    assertPlanRow(session, cursor, row, "block", 2, -1);
    assertPlanRow(session, cursor, row, "left", 2, -1);
    assertPlanRow(session, cursor, row, "table", -1, -1);
    assertPlanRow(session, cursor, row, "lookup", 1, -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertGroupedPlan(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN ANALYZE SELECT bucket,total FROM grouped_join", cursor));
    assertPlanRow(session, cursor, row, "block", 1, 1);
    assertPlanRow(session, cursor, row, "block", 2, 1);
    assertPlanRow(session, cursor, row, "having", 1, -1);
    assertPlanRow(session, cursor, row, "group", 1, -1);
    assertPlanRow(session, cursor, row, "sort", 0, -1);
    assertPlanRow(session, cursor, row, "block", 3, 2);
    assertPlanRow(session, cursor, row, "join", 2, -1);
    assertPlanRow(session, cursor, row, "table", -1, -1);
    assertPlanRow(session, cursor, row, "lookup", 1, -1);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertPlanRow(
      SqlSession session,
      SqlScanCursor cursor,
      SqlScanRowResult row,
      String operator,
      long detail,
      long actualRows) {
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack(operator), row.valueAt(0));
    assertEquals(detail, row.valueAt(1));
    assertEquals(actualRows < 0, row.isNull(2));
    if (actualRows >= 0) assertEquals(actualRows, row.valueAt(2));
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
        if (values[column] == 0 && row.isNull(column)) continue;
        assertEquals(values[column], row.valueAt(column));
      }
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static RelationalDatabaseOpenResult create(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    return opened;
  }

  private static SqlSession openSession(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
