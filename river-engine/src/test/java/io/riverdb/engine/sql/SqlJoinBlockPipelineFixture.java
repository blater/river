package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;

/** Shared real-path setup and row assertions for JOIN block pipeline evidence. */
final class SqlJoinBlockPipelineFixture {
  static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a4f494e424c4f43L, 0x4b50344232303031L);
  static final String HIGH_BMP = Character.toString(0xe000);

  final RelationalDatabase database;
  final SqlSession session;
  final SqlExecutionResult result;

  private SqlJoinBlockPipelineFixture(
      RelationalDatabase database, SqlSession session, SqlExecutionResult result) {
    this.database = database;
    this.session = session;
    this.result = result;
  }

  static SqlJoinBlockPipelineFixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            databaseRequest(8), root, DATABASE, WalGeneration.of(1), 8, opened));
    SqlSessionOpenResult sessionOpened = new SqlSessionOpenResult();
    RelationalDatabase database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionOpened));
    return new SqlJoinBlockPipelineFixture(
        database, sessionOpened.session(), new SqlExecutionResult());
  }

  void createBaseTables() {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE left_rows (id BIGINT PRIMARY KEY,bucket BIGINT,"
                + "label VARCHAR(20),observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK, session.execute("CREATE INDEX left_bucket ON left_rows(bucket)", result));
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
        session.execute("CREATE INDEX right_left_id ON right_rows(left_id)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO right_rows VALUES "
                + "(11,1,100,'猫',TRUE),(12,1,101,'ignored',FALSE),"
                + "(21,2,200,'😀',TRUE),(31,3,300,'fox',TRUE)",
            result));
  }

  static void assertRows(
      SqlSession session, SqlExecutionResult result, String sql, long[][] expected) {
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

  static void assertTextRows(
      SqlSession session, SqlExecutionResult result, String sql, String[] expected) {
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

  static void assertSingleNull(SqlSession session, SqlExecutionResult result, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertTrue(row.isNull(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  static void assertRowCount(
      SqlSession session, SqlExecutionResult result, String sql, int expected) {
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
}
