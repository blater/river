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

final class SqlNullableIndexTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4e554c4c41424c45L, 0x494e444558303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void indexesNonNullValuesWithoutHidingNullableRows(@TempDir Path root) {
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
            "CREATE TABLE events "
                + "(id BIGINT PRIMARY KEY, category BIGINT, bucket BIGINT, "
                + "amount BIGINT NOT NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1, NULL, NULL, 100), (2, NULL, NULL, 200), "
                + "(3, 10, 7, 300), (4, 20, 7, 400)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE UNIQUE INDEX events_category ON events(category)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_bucket ON events(bucket)", result));

    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO events VALUES (5, NULL, NULL, 500)", result));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("INSERT INTO events VALUES (6, 10, 8, 600)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET category=30, bucket=8 WHERE id=5", result));
    assertEquals(
        StatusCode.OK,
        session.execute("UPDATE events SET category=NULL, bucket=NULL WHERE id=3", result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO events VALUES (6, 10, 7, 600)", result));

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id, category FROM events WHERE category=10", result));
    assertEquals(6, result.key());
    assertBucketMembers(session, 7, 4, 6);
    assertNullableOrder(session);
    assertDistinctCategories(session);
    assertCategoryGroups(session);

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
        session.execute("SELECT id FROM events WHERE category=30", result));
    assertEquals(5, result.key());
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM events WHERE id=1", result));
    assertNullableOrderAfterDelete(session);
    assertBucketMembers(session, 7, 4, 6);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertBucketMembers(
      SqlSession session,
      long bucket,
      long firstExpected,
      long secondExpected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id FROM events WHERE bucket=" + bucket,
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    long first = row.key();
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    long second = row.key();
    assertTrue(first == firstExpected && second == secondExpected
        || first == secondExpected && second == firstExpected);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }

  private static void assertNullableOrder(SqlSession session) {
    assertNullableOrder(session, new long[] {1, 2, 3, 6, 4, 5});
  }

  private static void assertNullableOrderAfterDelete(SqlSession session) {
    assertNullableOrder(session, new long[] {2, 3, 6, 4, 5});
  }

  private static void assertNullableOrder(SqlSession session, long[] expectedKeys) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT id, category FROM events ORDER BY category", cursor));
    for (int index = 0; index < expectedKeys.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(expectedKeys[index], row.key());
      assertEquals(index < (expectedKeys.length == 6 ? 3 : 2), row.isNull(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }

  private static void assertDistinctCategories(SqlSession session) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT DISTINCT category FROM events ORDER BY category", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertTrue(row.isNull(0));
    long[] expected = {10, 20, 30};
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }

  private static void assertCategoryGroups(SqlSession session) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT category, COUNT(*) FROM events "
                + "GROUP BY category ORDER BY category",
            cursor));
    long[] expectedCategories = {0, 10, 20, 30};
    long[] expectedCounts = {3, 1, 1, 1};
    for (int index = 0; index < expectedCategories.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(index == 0, row.isNull(0));
      assertEquals(expectedCategories[index], row.valueAt(0));
      assertEquals(expectedCounts[index], row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }
}
