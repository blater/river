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

final class SqlGroupedAggregateTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x47524f5550414747L, 0x5245474154453031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void spillsAndMergesUnindexedGroupsWithoutCollapsingNullIntoZero(
      @TempDir Path root) {
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
            "CREATE TABLE samples "
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            result));

    long[] sums = new long[8];
    boolean[] values = new boolean[8];
    int rows = 1_100;
    for (int start = 1; start <= rows; start += 64) {
      int end = Math.min(rows, start + 63);
      StringBuilder insert = new StringBuilder("INSERT INTO samples VALUES ");
      for (int id = start; id <= end; id++) {
        if (id > start) {
          insert.append(',');
        }
        boolean nullCategory = id % 100 == 0;
        boolean nullAmount = id % 13 == 0;
        insert.append('(').append(id).append(',');
        if (nullCategory) {
          insert.append("NULL");
        } else {
          insert.append(id % 7);
        }
        insert.append(',');
        if (nullAmount) {
          insert.append("NULL");
        } else {
          insert.append(id);
          int group = nullCategory ? 0 : id % 7 + 1;
          sums[group] += id;
          values[group] = true;
        }
        insert.append(')');
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    }

    String grouped =
        "SELECT category, SUM(amount) FROM samples GROUP BY category ORDER BY category";
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(grouped, cursor));
    for (int index = 0; index < sums.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(index == 0, row.isNull(0));
      assertEquals(index == 0 ? 0 : index - 1, row.valueAt(0));
      assertEquals(!values[index], row.isNull(1));
      assertEquals(sums[index], row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(StatusCode.OK, session.beginScan(grouped + " LIMIT 1", cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertGroups(
        session,
        "SELECT category, SUM(amount) FROM samples "
            + "WHERE category=3 GROUP BY category",
        "sum",
        new long[] {3},
        new long[] {sums[4]},
        0);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void streamsGroupedValueAggregatesWithNullAndOverflowSemantics(
      @TempDir Path root) {
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
                + "(id BIGINT PRIMARY KEY, category BIGINT, amount BIGINT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1, 10, 100), (2, 10, 200), (3, 10, NULL), "
                + "(4, 20, 300), (5, 20, 500), (6, 30, NULL), "
                + "(7, 40, 9223372036854775807), (8, 40, 1)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX events_category ON events(category)", result));

    assertGroups(
        session,
        "SELECT category, SUM(amount) AS total FROM events "
            + "WHERE category >= 10 AND category < 40 "
            + "GROUP BY category ORDER BY category LIMIT 3",
        "total",
        new long[] {10, 20, 30},
        new long[] {300, 800, 0},
        1L << 2);
    assertGroups(
        session,
        "SELECT category, COUNT(amount) FROM events "
            + "GROUP BY category ORDER BY category",
        "count",
        new long[] {10, 20, 30, 40},
        new long[] {2, 2, 0, 2},
        0);
    assertGroups(
        session,
        "SELECT category, MIN(amount) FROM events "
            + "GROUP BY category ORDER BY category",
        "min",
        new long[] {10, 20, 30, 40},
        new long[] {100, 300, 0, 1},
        1L << 2);
    assertGroups(
        session,
        "SELECT category, MAX(amount) FROM events "
            + "GROUP BY category ORDER BY category",
        "max",
        new long[] {10, 20, 30, 40},
        new long[] {200, 500, 0, Long.MAX_VALUE},
        1L << 2);
    assertGroups(
        session,
        "SELECT category, SUM(amount) FROM events "
            + "WHERE category < 40 GROUP BY category "
            + "HAVING SUM(amount) >= 300 ORDER BY category",
        "sum",
        new long[] {10, 20},
        new long[] {300, 800},
        0);
    assertGroups(
        session,
        "SELECT category, SUM(amount) FROM events "
            + "WHERE category < 40 GROUP BY category "
            + "HAVING SUM(amount) > 300 ORDER BY category LIMIT 1",
        "sum",
        new long[] {20},
        new long[] {800},
        0);
    assertGroups(
        session,
        "SELECT category, COUNT(amount) FROM events "
            + "GROUP BY category HAVING COUNT(amount) = 0 ORDER BY category",
        "count",
        new long[] {30},
        new long[] {0},
        0);
    assertGroups(
        session,
        "SELECT category, MIN(amount) FROM events "
            + "GROUP BY category HAVING MIN(amount) < 200 ORDER BY category",
        "min",
        new long[] {10, 40},
        new long[] {100, 1},
        0);
    assertGroups(
        session,
        "SELECT category, COUNT(*) FROM events "
            + "GROUP BY category HAVING COUNT(*) > 99 ORDER BY category",
        "count",
        new long[0],
        new long[0],
        0);

    SqlScanCursor overflow = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT category, SUM(amount) FROM events "
                + "WHERE category=40 GROUP BY category",
            overflow));
    assertEquals(
        StatusCode.NUMERIC_VALUE_OUT_OF_RANGE,
        session.nextScan(overflow, row));
    assertEquals(StatusCode.OK, session.closeScan(overflow, result));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessionResult));
    session = sessionResult.session();
    assertGroups(
        session,
        "SELECT category, SUM(amount) FROM events "
            + "WHERE category=20 GROUP BY category",
        "sum",
        new long[] {20},
        new long[] {800},
        0);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertGroups(
      SqlSession session,
      String sql,
      String aggregateName,
      long[] groups,
      long[] aggregates,
      long nullGroups) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult closed = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(true, "category".contentEquals(session.scanColumnName(cursor, 0)));
    assertEquals(true, aggregateName.contentEquals(session.scanColumnName(cursor, 1)));
    for (int index = 0; index < groups.length; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(groups[index], row.valueAt(0));
      assertEquals(aggregates[index], row.valueAt(1));
      assertEquals((nullGroups & 1L << index) != 0, row.isNull(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, session.closeScan(cursor, closed));
  }
}
