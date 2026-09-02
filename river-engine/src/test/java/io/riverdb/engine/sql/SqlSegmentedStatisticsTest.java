package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.engine.checkpoint.CheckpointResult;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public ANALYZE proof for the maximum table shape and durable planner consumption. */
final class SqlSegmentedStatisticsTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5345475354415431L, 0x303234434f4c554dL);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final int COLUMNS = 1_024;

  @Test
  void analyzeOneThousandTwentyFourColumnsSurvivesReopenAndCostsSelfJoin(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 8, opened));
    RelationalDatabase database = opened.database();
    SqlSession session = session(database);
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.execute(createSql(), result));
    assertEquals(StatusCode.OK, session.execute(insertSql(), result));
    assertEquals(StatusCode.OK, session.execute("ANALYZE TABLE stats_wide", result));
    assertEquals(1, result.affectedRows());
    assertPlanContainsStatistics(session);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.checkpoint(new CheckpointResult()));
    assertEquals(StatusCode.OK, database.close());

    opened.reset();
    assertEquals(StatusCode.OK,
        RelationalDatabase.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    session = session(database);
    assertPlanContainsStatistics(session);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertPlanContainsStatistics(SqlSession session) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(
        "EXPLAIN SELECT a.c0,b.c0 FROM stats_wide a "
            + "JOIN stats_wide b ON a.c0=b.c0", cursor));
    boolean exact = false;
    while (session.nextScan(cursor, row).isOk()) {
      exact |= row.valueAt(0) == PackedText.pack("exact");
    }
    assertTrue(exact);
    assertEquals(StatusCode.OK, session.closeScan(cursor, new SqlExecutionResult()));
  }

  private static SqlSession session(RelationalDatabase database) {
    SqlSessionOpenResult opened = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, opened));
    return opened.session();
  }

  private static String createSql() {
    StringBuilder sql = new StringBuilder("CREATE TABLE stats_wide (");
    for (int column = 0; column < COLUMNS; column++) {
      if (column > 0) sql.append(',');
      sql.append('c').append(column).append(column == 0 ? " BIGINT PRIMARY KEY" : " BIGINT");
    }
    return sql.append(')').toString();
  }

  private static String insertSql() {
    StringBuilder sql = new StringBuilder("INSERT INTO stats_wide VALUES (1");
    for (int column = 1; column < COLUMNS; column++) sql.append(",NULL");
    return sql.append(')').toString();
  }
}
