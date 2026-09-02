package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path coverage for direct-root scalar aggregate expression operands. */
final class EmbeddedRiverTemporalAggregateExpressionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x54454d5041474752L, 0x4547415445303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void aggregatesColumnBearingTemporalProgramsAndCleansUpFailures(
      @TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    createFixture(session, result);

    assertAggregateResults(session, result);
    assertSharedTemporalSnapshot(session, result);
    assertComputedPredicateAggregates(session, result);
    assertBoundariesAndCleanup(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertSharedTemporalSnapshot(
      RiverSession session, CommandResult result) {
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE '+14:00'", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MIN(CURRENT_DATE) FROM aggregate_moments", result));
    long easternDate = result.valueAt(0);
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE '-12:00'", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MIN(CURRENT_DATE) FROM aggregate_moments", result));
    long westernDate = result.valueAt(0);
    assertEquals(true, easternDate > westernDate);
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE 'UTC'", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT MAX(CURRENT_TIMESTAMP) FROM aggregate_moments "
                + "HAVING MAX(CURRENT_TIMESTAMP)=CURRENT_TIMESTAMP",
            result));
    assertEquals(true, result.rowAvailable());
  }

  private static void createFixture(RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE aggregate_moments (id BIGINT PRIMARY KEY, day DATE, "
                + "observed TIMESTAMP(6), captured TIMESTAMP(6) WITH TIME ZONE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO aggregate_moments VALUES "
                + "(1,DATE '2024-02-28',TIMESTAMP '2024-02-28 10:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00'),"
                + "(2,DATE '2024-02-29',TIMESTAMP '2024-02-29 11:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-29 11:00:00+00:00'),"
                + "(3,NULL,NULL,NULL),"
                + "(4,DATE '2024-03-31',TIMESTAMP '2024-03-31 01:30:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-03-31 01:30:00+00:00')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE null_moments (id BIGINT PRIMARY KEY, observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO null_moments VALUES (1,NULL)", result));
  }

  private static void assertAggregateResults(
      RiverSession session, CommandResult result) {
    assertValue(
        session,
        result,
        "SELECT COUNT(EXTRACT(DAY FROM day)) FROM aggregate_moments",
        3,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT COUNT(EXTRACT(SECOND FROM observed)) FROM aggregate_moments",
        3,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT SUM(day-DATE '2024-02-27') FROM aggregate_moments",
        36,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT AVG(day-DATE '2024-02-27') FROM aggregate_moments",
        12_000_000,
        SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 6));

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM aggregate_moments WHERE id=1", result));
    long firstDay = result.valueAt(0);
    assertValue(
        session,
        result,
        "SELECT MIN(CAST(day AS TIMESTAMP(3))) FROM aggregate_moments",
        firstDay * LocalTemporal.MICROSECONDS_PER_DAY,
        SqlTypeDescriptor.timestamp(3));
    assertValue(
        session,
        result,
        "SELECT MAX(observed AT TIME ZONE 'UTC') FROM aggregate_moments",
        (firstDay + 32) * LocalTemporal.MICROSECONDS_PER_DAY
            + 90 * 60 * LocalTemporal.MICROSECONDS_PER_SECOND,
        SqlTypeDescriptor.timestampWithTimeZone(6));
    assertValue(
        session,
        result,
        "SELECT COUNT(day+NULL) FROM aggregate_moments",
        0,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT COUNT(day+(CURRENT_DATE-day)) FROM aggregate_moments",
        3,
        SqlTypeDescriptor.BIGINT);
    assertNullAggregate(
        session,
        result,
        "SELECT MIN(CAST(observed AS TIMESTAMP(3))) FROM null_moments",
        SqlTypeDescriptor.timestamp(3));
    assertNullAggregate(
        session,
        result,
        "SELECT MAX(observed AT TIME ZONE 'UTC') FROM null_moments",
        SqlTypeDescriptor.timestampWithTimeZone(6));
  }

  private static void assertBoundariesAndCleanup(
      RiverSession session, CommandResult result) {
    assertTextValue(
        session,
        result,
        "SELECT MIN(CAST(day AS VARCHAR(10))) FROM aggregate_moments",
        "2024-02-28");
    assertValue(
        session,
        result,
        "SELECT MIN(DATE '2024-01-01') FROM aggregate_moments",
        firstEpochDay(session, result) - 58,
        SqlTypeDescriptor.DATE);
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute(
            "SELECT SUM(CAST(day AS TIMESTAMP(3))) FROM aggregate_moments", result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT MIN(observed AT TIME ZONE 'Not/A_Real_Zone') FROM null_moments",
            result));
    assertValue(
        session,
        result,
        "SELECT MAX(observed AT TIME ZONE 'Europe/London') "
            + "FROM aggregate_moments WHERE id<4",
        (firstEpochDay(session, result) + 1) * LocalTemporal.MICROSECONDS_PER_DAY
            + 11 * 60 * 60 * LocalTemporal.MICROSECONDS_PER_SECOND,
        SqlTypeDescriptor.timestampWithTimeZone(6));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT MAX(observed AT TIME ZONE 'Europe/London') "
                + "FROM aggregate_moments",
            result));
    assertValue(
        session,
        result,
        "SELECT COUNT(EXTRACT(DAY FROM day)) FROM aggregate_moments",
        3,
        SqlTypeDescriptor.BIGINT);
  }

  private static void assertComputedPredicateAggregates(
      RiverSession session, CommandResult result) {
    String dateRange = " WHERE day+0 BETWEEN "
        + "DATE '2024-02-28' AND DATE '2024-02-29'";
    assertValue(
        session,
        result,
        "SELECT COUNT(*) FROM aggregate_moments" + dateRange,
        2,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT COUNT(EXTRACT(DAY FROM day)) FROM aggregate_moments" + dateRange,
        2,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT SUM(day-DATE '2024-02-27') FROM aggregate_moments" + dateRange,
        3,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT AVG(day-DATE '2024-02-27') FROM aggregate_moments" + dateRange,
        1_500_000,
        SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 6));

    long firstDay = firstEpochDay(session, result);
    assertValue(
        session,
        result,
        "SELECT MIN(CAST(day AS TIMESTAMP(3))) FROM aggregate_moments "
            + "WHERE id BETWEEN 1 AND 3 AND day+0 IN ("
            + "DATE '2024-02-28',DATE '2024-02-29')",
        firstDay * LocalTemporal.MICROSECONDS_PER_DAY,
        SqlTypeDescriptor.timestamp(3));
    assertValue(
        session,
        result,
        "SELECT MAX(observed AT TIME ZONE 'UTC') FROM aggregate_moments "
            + "WHERE CAST(captured AS TIMESTAMP(6) WITH TIME ZONE) IN ("
            + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00',"
            + "TIMESTAMP WITH TIME ZONE '2024-02-29 11:00:00.000000+00:00')",
        (firstDay + 1) * LocalTemporal.MICROSECONDS_PER_DAY
            + 11 * 60 * 60 * LocalTemporal.MICROSECONDS_PER_SECOND,
        SqlTypeDescriptor.timestampWithTimeZone(6));
    assertValue(
        session,
        result,
        "SELECT COUNT(*) FROM aggregate_moments WHERE day+0 IN ("
            + "DATE '2000-01-01')",
        0,
        SqlTypeDescriptor.BIGINT);
    assertValue(
        session,
        result,
        "SELECT COUNT(*) FROM aggregate_moments WHERE "
            + "CAST(captured AS TIMESTAMP(6) WITH TIME ZONE) NOT IN ("
            + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00',NULL)",
        0,
        SqlTypeDescriptor.BIGINT);
    assertNullAggregate(
        session,
        result,
        "SELECT MIN(CAST(day AS TIMESTAMP(3))) FROM aggregate_moments "
            + "WHERE day+0 IN (DATE '2000-01-01')",
        SqlTypeDescriptor.timestamp(3));
    assertNullAggregate(
        session,
        result,
        "SELECT SUM(day-DATE '2024-02-27') FROM aggregate_moments "
            + "WHERE day+0 IN (DATE '2000-01-01')",
        SqlTypeDescriptor.BIGINT);

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT COUNT(*) FROM null_moments WHERE observed AT TIME ZONE "
                + "'Not/A_Real_Zone' IN (TIMESTAMP WITH TIME ZONE "
                + "'2024-01-01 00:00:00+00:00')",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT COUNT(*) FROM aggregate_moments WHERE observed AT TIME ZONE "
                + "'Europe/London' IN (TIMESTAMP WITH TIME ZONE "
                + "'2024-02-28 10:00:00+00:00')",
            result));
    assertValue(
        session,
        result,
        "SELECT COUNT(*) FROM aggregate_moments" + dateRange,
        2,
        SqlTypeDescriptor.BIGINT);
  }

  private static void assertValue(
      RiverSession session,
      CommandResult result,
      String sql,
      long expected,
      int descriptor) {
    assertEquals(StatusCode.OK, session.execute(sql, result), sql);
    assertEquals(expected, result.valueAt(0));
    assertEquals(descriptor, result.typeDescriptorAt(0));
  }

  private static void assertNullAggregate(
      RiverSession session,
      CommandResult result,
      String sql,
      int descriptor) {
    assertEquals(StatusCode.OK, session.execute(sql, result), sql);
    assertEquals(true, result.isNull(0));
    assertEquals(descriptor, result.typeDescriptorAt(0));
  }

  private static void assertTextValue(
      RiverSession session,
      CommandResult result,
      String sql,
      String expected) {
    assertEquals(StatusCode.OK, session.execute(sql, result));
    assertEquals(SqlTypeDescriptor.varchar(10), result.typeDescriptorAt(0));
    char[] text = new char[16];
    int length = result.copyTextAt(0, text, 0);
    assertEquals(expected.length(), length);
    for (int index = 0; index < length; index++) {
      assertEquals(expected.charAt(index), text[index]);
    }
  }

  private static long firstEpochDay(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM aggregate_moments WHERE id=1", result));
    return result.valueAt(0);
  }
}
