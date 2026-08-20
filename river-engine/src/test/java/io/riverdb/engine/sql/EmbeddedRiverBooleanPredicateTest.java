package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path evidence for the common bounded SQL-3VL predicate program. */
final class EmbeddedRiverBooleanPredicateTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x424f4f4c50524544L, 0x4943415445303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void evaluatesBooleanProgramsAcrossDirectAggregateBlockAndDml(
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
    createFixture(session, result);

    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE NOT (flag OR amount<0) ORDER BY id",
        2);
    assertRows(session, result, "SELECT id FROM facts WHERE flag ORDER BY id", 1);
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE flag IS UNKNOWN ORDER BY id",
        3);
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE amount BETWEEN NULL AND 30 ORDER BY id");
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE amount IN (10,NULL) ORDER BY id",
        1);
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE amount NOT IN (10,NULL) ORDER BY id");
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE CAST(day AS VARCHAR(10)) BETWEEN "
            + "'2024-01-01' AND '2024-01-02' ORDER BY id",
        1,
        2);
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE CAST(day AS VARCHAR(10)) IN "
            + "('2024-01-01',NULL) ORDER BY id",
        1);
    assertShortCircuitAndPreflight(session, result);
    assertTypedMarkers(session, result);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE facts SET amount=amount+1 "
                + "WHERE NOT flag AND amount BETWEEN 10 AND 25",
            result));
    assertEquals(1, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT amount FROM facts WHERE id=2", result));
    assertEquals(21, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM facts WHERE flag IS UNKNOWN", result));
    assertEquals(1, result.affectedRows());

    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM facts HAVING "
                + "COUNT(*)>1 AND NOT (COUNT(*)=0)",
            result));
    assertEquals(3, result.valueAt(0));
    assertRows(
        session,
        result,
        "SELECT COUNT(*) FROM facts HAVING COUNT(*)=NULL");
    assertRows(
        session,
        result,
        "SELECT id FROM (SELECT id,amount FROM facts WHERE amount+0>=10) q "
            + "WHERE NOT (amount>21) ORDER BY id",
        1,
        2);

    assertPointTemporalCleanup(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFixture(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE facts (id BIGINT PRIMARY KEY, amount BIGINT, "
                + "flag BOOLEAN, label VARCHAR(20), day DATE, "
                + "observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO facts VALUES "
                + "(1,10,TRUE,'猫',DATE '2024-01-01',"
                + "TIMESTAMP '2024-01-01 12:00:00'),"
                + "(2,20,FALSE,'😀',DATE '2024-01-02',"
                + "TIMESTAMP '2024-01-02 12:00:00'),"
                + "(3,30,NULL,'猫',DATE '2024-01-03',"
                + "TIMESTAMP '2024-03-31 01:30:00'),"
                + "(4,NULL,FALSE,NULL,NULL,NULL)",
            result));
  }

  private static void assertPointTemporalCleanup(
      SqlSession session, SqlExecutionResult result) {
    String predicate = "observed AT TIME ZONE 'Europe/London'>="
        + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'";
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT id FROM facts WHERE id=1 AND " + predicate,
            result));
    assertEquals(1, result.valueAt(0));
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT COUNT(*) FROM facts WHERE " + predicate
                + " HAVING MAX(observed) AT TIME ZONE 'Europe/London'>="
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'",
            cursor));
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(2, row.valueAt(0));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM facts WHERE id=1", result));
    assertEquals(1, result.valueAt(0));
  }

  private static void assertShortCircuitAndPreflight(
      SqlSession session, SqlExecutionResult result) {
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE id=3 OR observed AT TIME ZONE "
            + "'Europe/London'>=TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 00:00:00+00:00' ORDER BY id",
        1,
        2,
        3);
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE id<0 AND observed AT TIME ZONE "
            + "'Europe/London'>=TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 00:00:00+00:00'");
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginScan(
            "SELECT id FROM facts WHERE id=1 OR observed AT TIME ZONE "
                + "'Not/A_Real_Zone'>=TIMESTAMP WITH TIME ZONE "
                + "'2024-01-01 00:00:00+00:00'",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM facts WHERE id=1", result));
  }

  private static void assertTypedMarkers(
      SqlSession session, SqlExecutionResult result) {
    ParameterSet parameters = new ParameterSet(2, 0);
    assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, 1));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM facts WHERE ?=id", parameters, result));
    assertEquals(1, result.valueAt(0));
    parameters.reset();
    assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, 7));
    assertEquals(StatusCode.OK, parameters.appendFixed(SqlTypeDescriptor.BIGINT, 7));
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE id+?=?+id ORDER BY id",
        parameters,
        1,
        2,
        3,
        4);
    parameters.reset();
    assertEquals(StatusCode.OK, parameters.appendNull(SqlTypeDescriptor.BIGINT));
    assertRows(
        session,
        result,
        "SELECT id FROM facts WHERE ?=id ORDER BY id",
        parameters);
    parameters.reset();
    assertEquals(
        StatusCode.PARAMETER_COUNT_MISMATCH,
        session.execute("SELECT id FROM facts WHERE ?=id", parameters, result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM facts WHERE id=1", result));
  }

  private static void assertRows(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, cursor));
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertTrue(row.isAvailable());
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }

  private static void assertRows(
      SqlSession session,
      SqlExecutionResult result,
      String sql,
      ParameterSet parameters,
      long... expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, session.beginScan(sql, parameters, cursor));
    for (long value : expected) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(value, row.valueAt(0));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
  }
}
