package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlJoinIndexRootCacheTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4a4f494e524f4f54L, 0x4341434845303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final String QUERY = "SELECT l.id,s.quantity FROM lookup_left l "
      + "JOIN lookup_stock s ON l.w=s.w AND l.item=s.item ORDER BY l.id";

  @Test
  void repeatedJoinProbesRespectNewStatementsAndTransactionSnapshots(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession reader = session(database);
    SqlSession writer = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    execute(reader, result,
        "CREATE TABLE lookup_left (id BIGINT PRIMARY KEY,w BIGINT,item BIGINT)");
    execute(reader, result,
        "CREATE TABLE lookup_stock (w BIGINT,item BIGINT,quantity BIGINT,"
            + "PRIMARY KEY(w,item))");
    execute(reader, result, "INSERT INTO lookup_left VALUES (1,1,10),(2,1,11),(3,1,99)");
    StringBuilder stock = new StringBuilder("INSERT INTO lookup_stock VALUES ");
    for (int item = 1; item <= 20; item++) {
      if (item > 1) stock.append(',');
      stock.append("(1,").append(item).append(',').append(item + 100).append(')');
    }
    execute(reader, result, stock.toString());
    execute(reader, result, "ANALYZE TABLE lookup_left");
    execute(reader, result, "ANALYZE TABLE lookup_stock");
    assertLeftRoot(reader);

    execute(reader, result, "BEGIN READ COMMITTED");
    assertPairs(reader, new long[][] {{1, 110}, {2, 111}});
    execute(writer, new SqlExecutionResult(),
        "INSERT INTO lookup_stock VALUES (1,99,199)");
    assertPairs(reader, new long[][] {{1, 110}, {2, 111}, {3, 199}});
    execute(reader, result, "COMMIT");

    execute(reader, result, "BEGIN REPEATABLE READ");
    assertPairs(reader, new long[][] {{1, 110}, {2, 111}, {3, 199}});
    execute(writer, new SqlExecutionResult(),
        "UPDATE lookup_stock SET quantity=999 WHERE w=1 AND item=11");
    assertPairs(reader, new long[][] {{1, 110}, {2, 111}, {3, 199}});
    execute(reader, result, "COMMIT");
    assertPairs(reader, new long[][] {{1, 110}, {2, 999}, {3, 199}});

    assertEquals(StatusCode.OK, reader.close());
    assertEquals(StatusCode.OK, writer.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }

  private static void assertLeftRoot(SqlSession session) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN ANALYZE " + QUERY, cursor));
    boolean root = false;
    boolean indexedInner = false;
    while (session.nextScan(cursor, row) == StatusCode.OK) {
      if (!root && (row.valueAt(0) == PackedText.pack("primary")
          || row.valueAt(0) == PackedText.pack("table"))) {
        assertEquals(3, row.valueAt(2));
        root = true;
      }
      indexedInner |= row.valueAt(0) == PackedText.pack("lookup");
    }
    assertEquals(true, root);
    assertEquals(true, indexedInner);
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }

  private static void assertPairs(SqlSession session, long[][] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(QUERY, cursor));
    for (long[] pair : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(pair[0], row.valueAt(0));
      assertEquals(pair[1], row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }

  private static void execute(SqlSession session, SqlExecutionResult result, String sql) {
    assertEquals(StatusCode.OK, session.execute(sql, result), sql);
  }
}
