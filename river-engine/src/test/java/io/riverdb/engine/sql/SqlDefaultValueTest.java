package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDefaultValueTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x44454641554c5453L, 0x424947494e543031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void persistsAndAppliesDefaultsOnlyToOmittedColumns(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE settings "
                + "(id BIGINT PRIMARY KEY, required BIGINT NOT NULL DEFAULT 7, "
                + "note BIGINT DEFAULT -1, optional BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX settings_required ON settings(required)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO settings (id) VALUES (1), (2)", result));
    assertEquals(2, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO settings (id, note) VALUES (3, NULL)", result));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT required, note, optional FROM settings WHERE id=1", result));
    assertEquals(7, result.valueAt(0));
    assertEquals(-1, result.valueAt(1));
    assertTrue(result.isNull(2));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT note FROM settings WHERE id=3", result));
    assertTrue(result.isNull(0));

    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "INSERT INTO settings (id, required) VALUES (4, 8), (5, NULL)",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT required FROM settings WHERE id=4", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO settings (id) VALUES (6)", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SELECT required FROM settings WHERE id=6", result));

    assertDefaultIndexRows(session, 3, 6);
    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO settings (id, optional) VALUES (7, 70)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT required, note, optional FROM settings WHERE id=7", result));
    assertEquals(7, result.valueAt(0));
    assertEquals(-1, result.valueAt(1));
    assertEquals(70, result.valueAt(2));
    assertDefaultIndexRows(session, 4, 13);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertDefaultIndexRows(
      SqlSession session,
      int expectedRows,
      long expectedKeySum) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM settings WHERE required=7", cursor));
    int rows = 0;
    long keySum = 0;
    StatusCode status = session.nextScan(cursor, row);
    while (status.isOk()) {
      keySum += row.key();
      rows++;
      status = session.nextScan(cursor, row);
    }
    assertEquals(StatusCode.CONFLICT, status);
    assertEquals(expectedRows, rows);
    assertEquals(expectedKeySum, keySum);
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }
}
