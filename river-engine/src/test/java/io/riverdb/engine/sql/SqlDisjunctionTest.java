package io.riverdb.engine.sql;

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

final class SqlDisjunctionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4449534a554e4354L, 0x494f4e53454d414eL);

  @Test
  void executesDisjunctionsThroughQueriesAggregatesAndDml(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(
            root, DATABASE, WalGeneration.of(1), 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult execution = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE choices "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO choices VALUES "
                + "(1,7,50),(2,7,150),(3,8,200),(4,9,300),(5,NULL,400),"
                + "(6,8,100)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX choices_category ON choices(category)", execution));

    assertRows(
        session,
        execution,
        "SELECT id FROM choices "
            + "WHERE category=7 OR category=8 AND amount>150 ORDER BY id",
        1,
        2,
        3);
    assertRows(
        session,
        execution,
        "SELECT id FROM choices WHERE category=9 OR category IS NULL ORDER BY id",
        4,
        5);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM choices WHERE category=7 OR amount>=300",
            execution));
    assertEquals(4, execution.value());

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE choices SET amount=999 WHERE id=1 OR id=3", execution));
    assertEquals(2, execution.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM choices WHERE amount=999", execution));
    assertEquals(2, execution.value());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "DELETE FROM choices WHERE id=2 OR category IS NULL", execution));
    assertEquals(2, execution.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM choices", execution));
    assertEquals(4, execution.value());

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT id FROM choices WHERE category=7 OR category=8",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("filter"), row.valueAt(0));
    assertEquals(2, row.valueAt(1));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertTrue(row.isVarchar(0));
    assertEquals(PackedText.pack("table"), row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRows(
      SqlSession session,
      SqlExecutionResult execution,
      String sql,
      long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }
}
