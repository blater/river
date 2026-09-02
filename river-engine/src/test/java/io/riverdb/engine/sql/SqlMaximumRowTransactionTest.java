package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression for structural index versions beyond the public row-operation count. */
final class SqlMaximumRowTransactionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4D415854584E3130L, 0x3234524F57533031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int ROWS = 1_024;
  private static final int STATEMENT_ROWS = 64;

  @Test
  void commitsMaximumRowsPlusIndexRegistryVersionsAndReplays(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = openSession(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK,
        session.execute("CREATE TABLE capacity_rows (id BIGINT PRIMARY KEY,value BIGINT)", result));
    assertEquals(StatusCode.OK,
        session.execute("CREATE INDEX capacity_rows_value ON capacity_rows(value)", result));
    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    for (int first = 0; first < ROWS; first += STATEMENT_ROWS) {
      assertEquals(StatusCode.OK,
          session.execute(insert(first, first + STATEMENT_ROWS), result));
    }
    assertEquals(StatusCode.OK, session.execute("COMMIT", result));
    assertCount(session, result, ROWS);
    assertValueCount(session, 2_046, 1);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    opened.reset();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    session = openSession(database);
    assertCount(session, result, ROWS);
    assertValueCount(session, 2_046, 1);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static String insert(int first, int end) {
    StringBuilder sql = new StringBuilder("INSERT INTO capacity_rows VALUES ");
    for (int id = first; id < end; id++) {
      if (id > first) sql.append(',');
      sql.append('(').append(id).append(',').append(id * 2L).append(')');
    }
    return sql.toString();
  }

  private static void assertCount(
      SqlSession session, SqlExecutionResult result, long expected) {
    assertCount(session, result, "SELECT COUNT(*) FROM capacity_rows", expected);
  }

  private static void assertValueCount(SqlSession session, long value, long expected) {
    assertCount(session, new SqlExecutionResult(),
        "SELECT COUNT(*) FROM capacity_rows WHERE value=" + value, expected);
  }

  private static void assertCount(
      SqlSession session, SqlExecutionResult result, String sql, long expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(expected, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static SqlSession openSession(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }
}
