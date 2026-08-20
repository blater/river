package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path evidence for scoped computed INNER and LEFT JOIN programs. */
final class SqlComputedJoinTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x434f4d5055544544L, 0x4a4f494e30303031L);

  @Test
  void evaluatesSeparateOnAndWhereWithOwnedTypedOutput(@TempDir Path root) {
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
    createFixture(session, execution);

    assertPairs(
        session,
        execution,
        "SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
            + "ON l.amount+1=r.amount",
        1, 1);
    assertPairs(
        session,
        execution,
        "SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.id OR l.amount+1=r.amount",
        1, 1, 2, 2, 3, 3);
    assertPairs(
        session,
        execution,
        "SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
            + "ON r.id=l.id WHERE l.amount+r.amount>=40",
        2, 2, 3, 3);
    assertPairs(
        session,
        execution,
        "SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
            + "ON l.label>r.label AND l.id=2 AND r.id=4",
        2, 4);
    assertPairs(
        session,
        execution,
        "SELECT l.id,r.id FROM left_rows l LEFT JOIN right_rows r "
            + "ON l.id=r.id WHERE r.flag=FALSE OR r.id IS NULL",
        2, 2);
    assertLeftUnmatched(session, execution);
    assertUnknownOnNullExtends(session, execution);
    assertTypedMarkersAndMembership(session, execution);
    assertNonUniqueResidual(session, execution);
    assertOwnedTextAndComputedProjection(session, execution);
    assertReversedAccess(session, execution);
    assertAnalyzeAccessDistinction(session, execution);
    assertTemporalPreflight(session, execution);
    assertTemporalRuntimeCleanup(session, execution);
    assertLateProjectionFailureIsAtomic(session, execution);
    assertDirectOrderDeferred(session, execution);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFixture(
      SqlSession session, SqlExecutionResult execution) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE left_rows (id BIGINT PRIMARY KEY, amount BIGINT, "
                + "label VARCHAR(20), day DATE, observed TIMESTAMP(6))",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE right_dupes "
                + "(id BIGINT PRIMARY KEY, bucket BIGINT, amount BIGINT)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX right_dupes_bucket ON right_dupes(bucket)", execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO right_dupes VALUES "
                + "(10,10,10),(11,10,11),(20,20,21)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO left_rows VALUES "
                + "(1,10,'猫',DATE '2024-01-01',TIMESTAMP '2024-01-01 12:00:00'),"
                + "(2,20,'😀',DATE '2024-01-02',TIMESTAMP '2024-01-02 12:00:00'),"
                + "(3,30,'fox',DATE '2024-01-03',TIMESTAMP '2024-01-03 12:00:00')",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE right_rows (id BIGINT PRIMARY KEY, amount BIGINT, "
                + "flag BOOLEAN, label VARCHAR(20), day DATE)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO right_rows VALUES "
                + "(1,11,TRUE,'cat',DATE '2024-02-01'),"
                + "(2,20,FALSE,'😀',DATE '2024-02-02'),"
                + "(3,99,TRUE,'fox',DATE '2024-02-03'),"
                + "(4,40,TRUE,'',DATE '2024-02-04')",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE empty_times "
                + "(id BIGINT PRIMARY KEY, observed TIMESTAMP WITH TIME ZONE)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE gap_times "
                + "(id BIGINT PRIMARY KEY, observed TIMESTAMP(6))",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO gap_times VALUES "
                + "(1,TIMESTAMP '2024-03-31 00:30:00'),"
                + "(2,TIMESTAMP '2024-03-31 01:30:00')",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE zoned_times "
                + "(id BIGINT PRIMARY KEY, observed TIMESTAMP WITH TIME ZONE)",
            execution));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO zoned_times VALUES "
                + "(1,TIMESTAMP WITH TIME ZONE '2024-03-31 00:30:00+00:00'),"
                + "(2,TIMESTAMP WITH TIME ZONE '2024-03-31 02:30:00+01:00')",
            execution));
  }

  private static void assertLeftUnmatched(
      SqlSession session, SqlExecutionResult execution) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT l.id,r.amount+1 AS adjusted FROM left_rows l "
                + "LEFT JOIN right_rows r ON l.id=r.id AND r.flag=FALSE "
                + "WHERE r.id IS NULL",
            cursor));
    assertFalse(session.scanColumnIsNullable(cursor, 0));
    assertTrue(session.scanColumnIsNullable(cursor, 1));
    for (long id : new long[] {1, 3}) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(id, row.valueAt(0));
      assertTrue(row.isNull(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }

  private static void assertOwnedTextAndComputedProjection(
      SqlSession session, SqlExecutionResult execution) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT l.amount+r.amount AS total,r.label,"
                + "CAST(l.day AS VARCHAR(10)) AS rendered "
                + "FROM left_rows l JOIN right_rows r ON l.label=r.label",
            cursor));
    for (int index = 0; index < 2; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      if (row.valueAt(0) == 40) {
        assertText(row, 1, "😀");
        assertText(row, 2, "2024-01-02");
      } else {
        assertEquals(129, row.valueAt(0));
        assertText(row, 1, "fox");
        assertText(row, 2, "2024-01-03");
      }
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }

  private static void assertTypedMarkersAndMembership(
      SqlSession session, SqlExecutionResult execution) {
    ParameterSet parameters = new ParameterSet(5, 0);
    for (long value : new long[] {1, 0, 0, 10, 20}) {
      assertEquals(
          StatusCode.OK,
          parameters.appendFixed(SqlTypeDescriptor.BIGINT, value));
    }
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
                + "ON ?=l.id AND r.id+?=?+l.id "
                + "WHERE r.amount BETWEEN ? AND ?",
            parameters,
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    parameters.reset();
    assertEquals(
        StatusCode.OK, parameters.appendNull(SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, cursor.reset());
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT l.id,r.id FROM left_rows l LEFT JOIN right_rows r ON ?=l.id",
            parameters,
            cursor));
    for (long id = 1; id <= 3; id++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(id, row.valueAt(0));
      assertTrue(row.isNull(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));

    assertPairs(
        session,
        execution,
        "SELECT l.id,r.id FROM left_rows l LEFT JOIN right_rows r "
            + "ON l.id=r.id AND r.amount IN (NULL,20) "
            + "WHERE r.id IS NULL OR r.amount=20",
        1, 0, 2, 2, 3, 0);
  }

  private static void assertNonUniqueResidual(
      SqlSession session, SqlExecutionResult execution) {
    assertPairs(
        session,
        execution,
        "SELECT l.id,d.id FROM left_rows l JOIN right_dupes d "
            + "ON l.amount=d.bucket AND d.amount=l.amount+1",
        1, 11, 2, 20);
  }

  private static void assertUnknownOnNullExtends(
      SqlSession session, SqlExecutionResult execution) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT l.id,r.id FROM left_rows l LEFT JOIN right_rows r "
                + "ON l.id=r.id AND CAST(NULL AS BOOLEAN)",
            cursor));
    for (long id = 1; id <= 3; id++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(id, row.valueAt(0));
      assertTrue(row.isNull(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }

  private static void assertReversedAccess(
      SqlSession session, SqlExecutionResult execution) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "EXPLAIN SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
                + "ON r.id=l.id",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("join"), row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("table"), row.valueAt(0));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("lookup"), row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }

  private static void assertAnalyzeAccessDistinction(
      SqlSession session, SqlExecutionResult execution) {
    assertAnalyze(
        session,
        execution,
        "EXPLAIN ANALYZE SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.id AND l.amount+1=r.amount",
        2,
        "lookup");
    assertAnalyze(
        session,
        execution,
        "EXPLAIN ANALYZE SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
            + "ON l.amount+1=r.amount",
        1,
        "table");
  }

  private static void assertAnalyze(
      SqlSession session,
      SqlExecutionResult execution,
      String sql,
      long onLeaves,
      String rightAccess) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("join"), row.valueAt(0));
    assertEquals(onLeaves, row.valueAt(1));
    assertEquals(1, row.valueAt(2));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack("table"), row.valueAt(0));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(PackedText.pack(rightAccess), row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }

  private static void assertTemporalPreflight(
      SqlSession session, SqlExecutionResult execution) {
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginScan(
            "SELECT l.id,e.id FROM left_rows l JOIN empty_times e "
                + "ON l.observed AT TIME ZONE 'Not/A_Real_Zone'=e.observed",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM left_rows WHERE id=1", execution));
    assertEquals(10, execution.valueAt(0));
  }

  private static void assertTemporalRuntimeCleanup(
      SqlSession session, SqlExecutionResult execution) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT g.id,z.id FROM gap_times g JOIN zoned_times z "
                + "ON g.observed AT TIME ZONE 'Europe/London'=z.observed",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM left_rows WHERE id=2", execution));
    assertEquals(20, execution.valueAt(0));
  }

  private static void assertLateProjectionFailureIsAtomic(
      SqlSession session, SqlExecutionResult execution) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT g.id,z.id,g.observed AT TIME ZONE 'Europe/London' "
                + "FROM gap_times g JOIN zoned_times z ON g.id=z.id",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(1, row.valueAt(0));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(0, row.columnCount());
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM left_rows WHERE id=3", execution));
    assertEquals(30, execution.valueAt(0));
  }

  private static void assertDirectOrderDeferred(
      SqlSession session, SqlExecutionResult execution) {
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginScan(
            "SELECT l.id,r.id FROM left_rows l JOIN right_rows r "
                + "ON l.id=r.id ORDER BY l.id",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM left_rows WHERE id=1", execution));
    assertEquals(10, execution.valueAt(0));
  }

  private static void assertPairs(
      SqlSession session,
      SqlExecutionResult execution,
      String sql,
      long... pairs) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (int index = 0; index < pairs.length; index += 2) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(pairs[index], row.valueAt(0));
      assertEquals(pairs[index + 1], row.valueAt(1));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, execution));
  }

  private static void assertText(SqlScanRowResult row, int column, String expected) {
    char[] actual = new char[expected.length()];
    assertEquals(expected.length(), row.copyTextAt(column, actual, 0));
    for (int index = 0; index < actual.length; index++) {
      assertEquals(expected.charAt(index), actual[index]);
    }
  }
}
