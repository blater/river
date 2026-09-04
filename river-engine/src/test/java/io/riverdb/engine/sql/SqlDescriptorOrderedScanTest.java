package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorOrderedScanTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4453434f52444552L, 0x45445343414e3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void ordersAndFlattensNamedDescriptorScans(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE events "
            + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO events VALUES (1,10,100),(2,10,200),(3,20,300),"
            + "(4,10,400),(5,30,500)", result));

    assertRows(session,
        "SELECT amount AS category,id FROM events ORDER BY category",
        new long[] {100, 200, 400, 300, 500}, new long[] {1, 2, 4, 3, 5});
    assertRows(session,
        "SELECT id AS chosen,category FROM events ORDER BY chosen DESC LIMIT 1",
        new long[] {5}, new long[] {30});
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.beginScan(
        "SELECT id AS chosen,amount AS chosen FROM events ORDER BY chosen",
        new SqlScanCursor()));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, session.beginScan(
        "SELECT NULL AS chosen FROM events ORDER BY chosen", new SqlScanCursor()));
    assertRows(session,
        "SELECT d.id,d.amount FROM "
            + "(SELECT id,amount,category FROM events WHERE events.category=10) d "
            + "WHERE d.amount>=150 AND d.amount<450 ORDER BY amount LIMIT 2",
        new long[] {2, 4}, new long[] {200, 400});

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRows(
      SqlSession session, String sql, long[] first, long[] second) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (int index = 0; index < first.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(first[index], row.valueAt(0));
      assertEquals(second[index], row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
