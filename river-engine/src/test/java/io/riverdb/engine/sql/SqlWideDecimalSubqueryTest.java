package io.riverdb.engine.sql;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path two-lane decimal evidence across predicate-subquery execution. */
final class SqlWideDecimalSubqueryTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x5749444544454353L, 0x5542515545525931L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void preservesBothDecimalLanesAcrossScalarMembershipAndCorrelation(
      @TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();

    populate(session, result);

    // The first three scaled values have the same low lane. Only the high lane differs.
    assertRows(session,
        "SELECT id FROM wide_outer WHERE scaled="
            + "(SELECT scaled FROM wide_inner WHERE id=10)", 2);
    assertRows(session,
        "SELECT id FROM wide_outer WHERE scaled IN "
            + "(SELECT scaled FROM wide_inner WHERE id=10 OR id=12)", 2, 3);
    assertRows(session,
        "SELECT o.id FROM wide_outer o WHERE o.scaled="
            + "(SELECT i.scaled FROM wide_inner i WHERE i.owner=o.id)", 2, 3);
    assertRows(session,
        "SELECT o.id FROM wide_outer o WHERE EXISTS "
            + "(SELECT i.id FROM wide_inner i WHERE i.id=10 AND i.scaled=o.scaled)",
        2);
    assertRows(session,
        "SELECT id FROM wide_outer WHERE money="
            + "(SELECT money FROM wide_inner WHERE id=12)", 3);

    assertRows(session,
        "SELECT id FROM wide_outer WHERE scaled="
            + "(SELECT scaled FROM wide_inner WHERE id=13)");
    assertRows(session,
        "SELECT id FROM wide_outer WHERE scaled="
            + "(SELECT scaled FROM wide_inner WHERE id=999)");
    assertCardinality(session,
        "SELECT id FROM wide_outer WHERE scaled="
            + "(SELECT scaled FROM wide_inner WHERE id<=11)");

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void preservesBothDecimalLanesThroughDerivedConsumer(@TempDir Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(databaseRequest(7), root, DATABASE, GENERATION, 7, opened));
    RelationalDatabase database = opened.database();
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(database, sessions));
    SqlSession session = sessions.session();
    SqlExecutionResult result = new SqlExecutionResult();
    populate(session, result);

    // A derived consumer uses the graph cache and replays the retained high lane.
    assertRows(session,
        "SELECT id FROM (SELECT o.id FROM wide_outer o WHERE o.scaled IN "
            + "(SELECT i.scaled FROM wide_inner i WHERE i.id=10 OR i.id=12)) d",
        2, 3);
    assertDecimalRows(
        session,
        "SELECT scaled FROM (SELECT DISTINCT scaled FROM wide_outer WHERE id<=3) d "
            + "ORDER BY scaled",
        "-18.446744073709551615",
        "0.000000000000000001",
        "18.446744073709551617");
    assertDecimalGroup(
        session,
        "SELECT scaled,n FROM (SELECT scaled,COUNT(*) AS n FROM wide_outer "
            + "WHERE id<=3 GROUP BY scaled "
            + "HAVING scaled>0.000000000000000001) grouped",
        "18.446744073709551617",
        1);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void populate(SqlSession session, SqlExecutionResult result) {
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE wide_outer (id INTEGER PRIMARY KEY,"
            + "scaled DECIMAL(22,18),money DECIMAL(38,2))", result));
    assertEquals(StatusCode.OK, session.execute(
        "CREATE TABLE wide_inner (id INTEGER PRIMARY KEY,owner INTEGER,"
            + "scaled DECIMAL(22,18),money DECIMAL(38,2))", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO wide_outer VALUES "
            + "(1,0.000000000000000001,123456789012345678901234567890.12),"
            + "(2,18.446744073709551617,999999999999999999999999999999.99),"
            + "(3,-18.446744073709551615,-999999999999999999999999999999.99),"
            + "(4,NULL,NULL)", result));
    assertEquals(StatusCode.OK, session.execute(
        "INSERT INTO wide_inner VALUES "
            + "(10,2,18.446744073709551617,999999999999999999999999999999.99),"
            + "(11,1,18.446744073709551617,-999999999999999999999999999999.99),"
            + "(12,3,-18.446744073709551615,-999999999999999999999999999999.99),"
            + "(13,4,NULL,NULL)", result));
  }

  private static void assertRows(SqlSession session, String sql, long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
      assertEquals(value, row.valueAt(0), sql);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
  }

  private static void assertCardinality(SqlSession session, String sql) {
    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(StatusCode.CARDINALITY_VIOLATION, session.beginScan(sql, cursor), sql);
    assertFalse(cursor.isActive(), sql);
    assertEquals(StatusCode.OK, cursor.reset(), sql);
  }

  private static void assertDecimalRows(
      SqlSession session, String sql, String... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    for (String value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
      assertDecimal(row, value, sql);
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
  }

  private static void assertDecimalGroup(
      SqlSession session, String sql, String expected, long count) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor), sql);
    assertEquals(StatusCode.OK, session.nextScan(cursor, row), sql);
    assertDecimal(row, expected, sql);
    assertEquals(count, row.valueAt(1), sql);
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row), sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
  }

  private static void assertDecimal(
      SqlScanRowResult row, String expected, String sql) {
    BigInteger unscaled = new BigInteger(expected.replace(".", ""));
    assertEquals(unscaled.longValue(), row.valueAt(0), sql);
    assertEquals(unscaled.shiftRight(Long.SIZE).longValue(), row.highValueAt(0), sql);
  }
}
