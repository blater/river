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

final class SqlNestedQueryTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e45535445445458L, 0x5453454d414e3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void comparesCorrelatedAndLiteralVarcharByOwnedBytes(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE names "
                + "(id BIGINT PRIMARY KEY, value VARCHAR(32), number BIGINT)",
            result));

    SqlScanCursor explain = new SqlScanCursor();
    assertEquals(
        StatusCode.CONFLICT,
        session.beginScan(
            "EXPLAIN SELECT o.id FROM names o WHERE EXISTS "
                + "(SELECT i.id FROM missing_names i WHERE i.id=o.id)",
            explain));
    assertEquals(StatusCode.OK, explain.reset());
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginScan(
            "EXPLAIN SELECT o.id FROM names o WHERE EXISTS "
                + "(SELECT i.missing FROM names i WHERE i.id=o.id)",
            explain));
    assertEquals(StatusCode.OK, explain.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT o.id FROM names o WHERE EXISTS "
                + "(SELECT i.id FROM names i WHERE i.id=o.id)",
            explain));
    assertEquals(StatusCode.OK, session.closeScan(explain, result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT o.id FROM names o WHERE EXISTS "
                + "(SELECT i.id FROM names i WHERE i.id=o.id)",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT o.id FROM names o WHERE o.id="
                + "(SELECT i.id FROM names i WHERE i.id=o.id)",
            result));
    assertEquals(StatusCode.OK, explain.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT COUNT(*) FROM names o WHERE EXISTS "
                + "(SELECT i.id FROM names i WHERE i.id=o.id)",
            explain));
    assertEquals(StatusCode.OK, session.nextScan(explain, new SqlScanRowResult()));
    assertEquals(StatusCode.OK, session.closeScan(explain, result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO names VALUES "
                + "(1, 'aa', 10), (2, 'bb', 20), "
                + "(3, 'same-longer-than-seven', 30), "
                + "(4, 'same-longer-than-seven', 40), (5, NULL, 50)",
            result));

    assertRows(
        session,
        "SELECT o.id FROM names o WHERE EXISTS "
            + "(SELECT i.id FROM names i WHERE i.value=o.value AND i.id=1)",
        new long[] {1});
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE EXISTS "
            + "(SELECT i.id FROM names i WHERE i.value='bb' AND i.id=o.id)",
        new long[] {2});
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE EXISTS "
            + "(SELECT i.id FROM names i WHERE i.value=o.value AND i.id=3)",
        new long[] {3, 4});
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE o.value IN "
            + "(SELECT i.value FROM names i WHERE i.id=3)",
        new long[] {3, 4});
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE o.value IN "
            + "(SELECT i.value FROM names i WHERE i.number >= 30)",
        new long[] {3, 4});
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE o.value NOT IN "
            + "(SELECT i.value FROM names i WHERE i.number >= 30)",
        new long[0]);
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE o.value IN "
            + "(SELECT m.value FROM names m WHERE m.value IN "
            + "(SELECT i.value FROM names i WHERE i.id=m.id))",
        new long[] {1, 2, 3, 4});
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE EXISTS "
            + "(SELECT m.id FROM names m WHERE m.number="
            + "(SELECT i.number FROM names i WHERE i.id=m.id))",
        new long[] {1, 2, 3, 4, 5});
    assertRows(
        session,
        "SELECT o.id FROM names o WHERE o.id="
            + "(SELECT m.id FROM names m WHERE m.id="
            + "(SELECT i.id FROM names i WHERE i.id=1))",
        new long[] {1});
    SqlScanCursor invalid = new SqlScanCursor();
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginScan(
            "SELECT o.id FROM names o WHERE o.number IN "
                + "(SELECT m.number FROM names m WHERE m.value IN "
                + "(SELECT i.number FROM names i WHERE i.id=m.id))",
            invalid));
    assertEquals(StatusCode.OK, invalid.reset());
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginScan(
            "SELECT o.id FROM names o WHERE o.number IN "
                + "(SELECT m.number FROM names m WHERE m.number IN "
                + "(SELECT i.value FROM names i WHERE i.id=m.id))",
            invalid));
    assertEquals(StatusCode.OK, invalid.reset());
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginScan(
            "SELECT o.id FROM names o WHERE EXISTS "
                + "(SELECT i.id FROM names i WHERE i.value=o.number)",
            invalid));
    assertEquals(StatusCode.OK, invalid.reset());
    assertEquals(StatusCode.OK, session.execute("SELECT COUNT(*) FROM names", result));
    assertEquals(5, result.value());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void streamsMembershipBeyondLegacyCacheBoundAndRecovers(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessionResult = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    SqlSession session = sessionResult.session();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE candidates "
                + "(id BIGINT PRIMARY KEY, value BIGINT, region BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE probes "
                + "(id BIGINT PRIMARY KEY, value BIGINT, region BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO probes VALUES (1, 1, 7)", result));
    for (int first = 1; first <= 1_025; first += 32) {
      StringBuilder insert = new StringBuilder("INSERT INTO candidates VALUES ");
      int last = Math.min(first + 31, 1_025);
      for (int value = first; value <= last; value++) {
        if (value > first) {
          insert.append(',');
        }
        insert.append('(').append(value).append(',').append(value)
            .append(",7)");
      }
      assertEquals(
          StatusCode.OK,
          session.execute(insert.toString(), result),
          "candidate batch starting at " + first);
    }

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertRows(
        session,
        "SELECT id FROM probes WHERE value IN "
            + "(SELECT value FROM candidates WHERE id<=1024)",
        new long[] {1});
    assertRows(
        session,
        "SELECT id FROM probes WHERE value IN "
            + "(SELECT value FROM candidates LIMIT 1024)",
        new long[] {1});
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM probes WHERE value IN "
                + "(SELECT value FROM candidates LIMIT 1025)",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT p.id FROM probes p WHERE p.value IN "
                + "(SELECT c.value FROM candidates c WHERE c.region=p.region)",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(StatusCode.OK, session.execute("SELECT COUNT(*) FROM probes", result));
    assertEquals(1, result.value());
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertRows(
      SqlSession session, String sql, long[] expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
