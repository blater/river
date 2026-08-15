package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.QueryOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RiverSession;
import io.riverdb.engine.api.RowResult;
import io.riverdb.engine.api.SessionOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EmbeddedRiverTemporalTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4c4f43414c54494dL, 0x4553303030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void indexesFullWidthOrderedScalarsAndSignedPrimaryKeys(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE wide_scalars (id BIGINT PRIMARY KEY, "
                + "big_value BIGINT UNIQUE, amount DECIMAL(18,1) UNIQUE, "
                + "local_seen TIMESTAMP(6) UNIQUE, "
                + "captured TIMESTAMP(6) WITH TIME ZONE UNIQUE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO wide_scalars VALUES "
                + "(-9223372036854775808,-9223372036854775808,"
                + "-99999999999999999.9,TIMESTAMP '0001-01-01 00:00:00',"
                + "TIMESTAMP WITH TIME ZONE '0001-01-01 00:00:00+00:00'),"
                + "(0,0,0.0,TIMESTAMP '1970-01-01 00:00:00',"
                + "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00:00'),"
                + "(9223372036854775807,9223372036854775807,"
                + "99999999999999999.9,TIMESTAMP '9999-12-31 23:59:59.999999',"
                + "TIMESTAMP WITH TIME ZONE '9999-12-31 23:59:59.999999+00:00')",
            result));

    assertTableCount(
        session, result, "wide_scalars", "id=9223372036854775807", 1);
    assertTableCount(
        session, result, "wide_scalars", "big_value=9223372036854775807", 1);
    assertTableCount(
        session, result, "wide_scalars", "amount=99999999999999999.9", 1);
    assertTableCount(
        session,
        result,
        "wide_scalars",
        "local_seen=TIMESTAMP '9999-12-31 23:59:59.999999'",
        1);
    assertTableCount(
        session,
        result,
        "wide_scalars",
        "captured=TIMESTAMP WITH TIME ZONE '9999-12-31 23:59:59.999999+00:00'",
        1);
    assertIndexPlan(
        session, "SELECT id FROM wide_scalars WHERE big_value=9223372036854775807");
    assertIndexPlan(
        session,
        "SELECT id FROM wide_scalars WHERE local_seen="
            + "TIMESTAMP '9999-12-31 23:59:59.999999'");
    assertIndexPlan(
        session,
        "SELECT id FROM wide_scalars WHERE captured="
            + "TIMESTAMP WITH TIME ZONE '9999-12-31 23:59:59.999999+00:00'");
    assertIndexPlan(
        session,
        "SELECT id FROM wide_scalars WHERE big_value BETWEEN "
            + "-9223372036854775808 AND 9223372036854775806");

    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute(
            "INSERT INTO wide_scalars VALUES "
                + "(7,9223372036854775807,7.0,TIMESTAMP '2000-01-01 00:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00:00')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE wide_scalars SET big_value=-9223372036854775807, "
                + "amount=-99999999999999999.8, "
                + "local_seen=TIMESTAMP '0001-01-01 00:00:00.000001', "
                + "captured=TIMESTAMP WITH TIME ZONE "
                + "'0001-01-01 00:00:00.000001+00:00' "
                + "WHERE id=-9223372036854775808",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("DELETE FROM wide_scalars WHERE id=0", result));
    assertTableCount(session, result, "wide_scalars", "big_value=0", 0);
    assertOrderedIds(session, Long.MIN_VALUE, Long.MAX_VALUE);

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertTableCount(
        session, result, "wide_scalars", "big_value=-9223372036854775807", 1);
    assertTableCount(
        session,
        result,
        "wide_scalars",
        "captured=TIMESTAMP WITH TIME ZONE '0001-01-01 00:00:00.000001+00:00'",
        1);
    assertTableCount(
        session, result, "wide_scalars", "big_value=9223372036854775807", 1);
    assertIndexPlan(
        session, "SELECT id FROM wide_scalars WHERE amount=99999999999999999.9");
    assertOrderedIds(session, Long.MIN_VALUE, Long.MAX_VALUE);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void storesComparesDateIndexAndReopensLocalTemporalValues(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE moments (id BIGINT PRIMARY KEY, "
                + "day DATE DEFAULT DATE '2000-01-01', "
                + "alarm TIME(3), "
                + "observed TIMESTAMP(6) "
                + "CHECK (observed>=TIMESTAMP '1970-01-01 00:00:00'))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO moments VALUES "
                + "(1, DATE '2024-02-29', TIME '12:34:56.123', "
                + "TIMESTAMP '2024-02-29 12:34:56.123456'), "
                + "(2, DEFAULT, TIME '00:00:00', "
                + "TIMESTAMP '2000-01-01 00:00:00'), "
                + "(3, DATE '0001-01-01', TIME '23:59:59.999', NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX moments_day ON moments(day)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX moments_alarm ON moments(alarm)", result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX moments_observed ON moments(observed)", result));
    assertCount(session, result, "day=DATE '2024-02-29'", 1);
    assertCount(session, result, "day<DATE '2000-01-01'", 1);
    assertCount(session, result, "alarm>=TIME '12:34:56.123'", 2);
    assertCount(session, result, "alarm=TIME '23:59:59.999'", 1);
    assertIndexPlan(
        session,
        "SELECT id FROM moments WHERE alarm BETWEEN "
            + "TIME '12:34:56.123' AND TIME '23:59:59.999'");
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO moments VALUES (5, DATE '2024-02-29', "
                + "TIME '12:34:56.123', TIMESTAMP '2024-02-29 12:34:56')",
            result));
    assertCount(session, result, "alarm=TIME '12:34:56.123'", 2);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE moments SET alarm=TIME '12:34:56.123' WHERE id=2", result));
    assertCount(session, result, "alarm=TIME '12:34:56.123'", 3);
    assertEquals(
        StatusCode.OK, session.execute("DELETE FROM moments WHERE id=5", result));
    assertCount(session, result, "alarm=TIME '12:34:56.123'", 2);
    assertCount(
        session,
        result,
        "observed<TIMESTAMP '2024-03-01 00:00:00'",
        2);
    assertIndexPlan(
        session,
        "SELECT id FROM moments WHERE observed BETWEEN "
            + "TIMESTAMP '2000-01-01 00:00:00' AND "
            + "TIMESTAMP '2024-03-01 00:00:00'");
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute(
            "INSERT INTO moments VALUES (4, DATE '2024-01-01', "
                + "TIME '00:00:00.0001', TIMESTAMP '2024-01-01 00:00:00')",
            result));
    assertEquals(
        StatusCode.CHECK_VIOLATION,
        session.execute(
            "INSERT INTO moments VALUES (4, DATE '1969-12-31', "
                + "TIME '23:59:59.999', TIMESTAMP '1969-12-31 23:59:59')",
            result));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE schedules (id BIGINT PRIMARY KEY, slot TIME(6) UNIQUE)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO schedules VALUES "
                + "(1,TIME '00:00:00'),"
                + "(2,TIME '23:59:59.999999'),"
                + "(3,TIME '12:00:00.000001'),(4,NULL)",
            result));
    assertTimeCount(session, result, "slot=TIME '23:59:59.999999'", 1);
    assertTimeCount(session, result, "slot>=TIME '12:00:00.000001'", 2);
    assertIndexPlan(
        session,
        "SELECT id FROM schedules WHERE slot BETWEEN "
            + "TIME '12:00:00.000001' AND TIME '23:59:59.999999'");
    assertIndexPlan(
        session, "SELECT id FROM schedules WHERE slot=TIME '23:59:59.999999'");
    assertEquals(
        StatusCode.UNIQUE_VIOLATION,
        session.execute(
            "INSERT INTO schedules VALUES (5,TIME '23:59:59.999999')", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE schedules SET slot=TIME '06:30:00' WHERE id=3", result));
    assertTimeCount(session, result, "slot=TIME '12:00:00.000001'", 0);
    assertTimeCount(session, result, "slot=TIME '06:30:00'", 1);
    assertEquals(StatusCode.OK, session.execute("DELETE FROM schedules WHERE id=2", result));
    assertTimeCount(session, result, "slot=TIME '23:59:59.999999'", 0);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE moments SET alarm=TIME '01:02:03.456', "
                + "observed=TIMESTAMP '2024-02-29 12:34:56.123' WHERE id=1",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day, alarm, observed FROM moments WHERE id=1", result));
    assertTemporalRow(result);

    assertEquals(StatusCode.OK, session.execute("SELECT DATE '9999-12-31'", result));
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(0));
    assertEquals(LocalTemporal.MAXIMUM_EPOCH_DAY, result.valueAt(0));

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day, alarm, observed FROM moments WHERE id=1", result));
    assertTemporalRow(result);
    assertCount(session, result, "day=DATE '0001-01-01'", 1);
    assertCount(session, result, "alarm=TIME '12:34:56.123'", 1);
    assertCount(session, result, "alarm=TIME '23:59:59.999'", 1);
    assertCount(
        session,
        result,
        "observed=TIMESTAMP '2024-02-29 12:34:56.123'",
        1);
    assertIndexPlan(
        session,
        "SELECT id FROM moments WHERE observed="
            + "TIMESTAMP '2024-02-29 12:34:56.123'");
    assertTimeCount(session, result, "slot=TIME '06:30:00'", 1);
    assertIndexPlan(
        session,
        "SELECT id FROM schedules WHERE slot BETWEEN "
            + "TIME '00:00:00.000000' AND TIME '23:59:59.999999'");

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void comparesMixedPrecisionTemporalPredicates(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE temporal_predicates (id BIGINT PRIMARY KEY, "
                + "clock TIME(6), local_seen TIMESTAMP(6), "
                + "captured TIMESTAMP(6) WITH TIME ZONE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO temporal_predicates VALUES "
                + "(1,TIME '01:02:03',TIMESTAMP '2024-01-01 00:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'),"
                + "(2,TIME '01:02:03.100',TIMESTAMP '2024-01-01 00:00:00.100',"
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00.100+00:00'),"
                + "(3,TIME '01:02:03.123456',"
                + "TIMESTAMP '2024-01-01 00:00:00.123456',"
                + "TIMESTAMP WITH TIME ZONE "
                + "'2024-01-01 00:00:00.123456+00:00'),"
                + "(4,NULL,NULL,NULL)",
            result));

    String timeRange = "clock BETWEEN TIME '01:02:03' AND TIME '01:02:03.123456'";
    assertPredicateCount(session, result, timeRange, 3);
    assertPredicateCount(
        session,
        result,
        "clock IN (TIME '01:02:03',TIME '01:02:03.100')",
        2);
    assertPredicateCount(
        session,
        result,
        "clock NOT IN (TIME '01:02:03',NULL,TIME '01:02:03.123456')",
        0);

    assertPredicateCount(
        session,
        result,
        "local_seen BETWEEN TIMESTAMP '2024-01-01 00:00:00' "
            + "AND TIMESTAMP '2024-01-01 00:00:00.123456'",
        3);
    assertPredicateCount(
        session,
        result,
        "local_seen IN (TIMESTAMP '2024-01-01 00:00:00',"
            + "TIMESTAMP '2024-01-01 00:00:00.100')",
        2);
    assertPredicateCount(
        session,
        result,
        "local_seen NOT IN (TIMESTAMP '2024-01-01 00:00:00',NULL,"
            + "TIMESTAMP '2024-01-01 00:00:00.123456')",
        0);

    assertPredicateCount(
        session,
        result,
        "captured BETWEEN TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 00:00:00+00:00' AND TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 00:00:00.123456+00:00'",
        3);
    assertPredicateCount(
        session,
        result,
        "captured IN (TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00',"
            + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00.100+00:00')",
        2);
    assertPredicateCount(
        session,
        result,
        "captured NOT IN (TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 00:00:00+00:00',NULL,TIMESTAMP WITH TIME ZONE "
            + "'2024-01-01 00:00:00.123456+00:00')",
        0);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX temporal_predicates_clock "
                + "ON temporal_predicates(clock)",
            result));
    assertPredicateCount(session, result, timeRange, 3);
    assertIndexPlan(
        session, "SELECT id FROM temporal_predicates WHERE " + timeRange);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void resolvesZonedCurrentDefaultsOnceAndReopensCapturedValues(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE 'Europe/London'", result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute("SET TIME ZONE 'CET'", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE zoned_events (id BIGINT PRIMARY KEY, "
                + "captured TIMESTAMP(6) WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP, "
                + "wall TIMESTAMP(6) DEFAULT LOCALTIMESTAMP, "
                + "day DATE DEFAULT CURRENT_DATE, clock TIME(6) DEFAULT LOCALTIME, "
                + "fixed TIMESTAMP(6) WITH TIME ZONE DEFAULT "
                + "TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+01:00')",
            result));

    StringBuilder insert = new StringBuilder(
        "INSERT INTO zoned_events (id, captured, wall, day, clock, fixed) VALUES ");
    for (int row = 1; row <= 64; row++) {
      if (row > 1) {
        insert.append(',');
      }
      insert.append('(').append(row)
          .append(",DEFAULT,DEFAULT,DEFAULT,DEFAULT,DEFAULT)");
    }
    assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
    assertEquals(64, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX zoned_value ON zoned_events(fixed)", result));
    assertTableCount(
        session,
        result,
        "zoned_events",
        "fixed=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+01:00'",
        64);
    assertIndexPlan(
        session,
        "SELECT id FROM zoned_events WHERE fixed="
            + "TIMESTAMP WITH TIME ZONE '1999-12-31 23:00:00+00:00'");

    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT captured, wall, day, clock, fixed FROM zoned_events WHERE id=1",
            result));
    long[] inserted = values(result, 5);
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.timestamp(6), result.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(2));
    assertEquals(SqlTypeDescriptor.time(6), result.typeDescriptorAt(3));
    assertEquals(946_681_200_000_000L, result.valueAt(4));
    assertEquals(
        inserted[1],
        inserted[2] * LocalTemporal.MICROSECONDS_PER_DAY + inserted[3]);
    for (int row = 2; row <= 64; row++) {
      assertEquals(
          StatusCode.OK,
          session.execute(
              "SELECT captured, wall, day, clock, fixed FROM zoned_events WHERE id=" + row,
              result));
      assertValues(inserted, result);
    }

    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE zoned_events SET captured=DEFAULT, wall=DEFAULT "
                + "WHERE id>=1 AND id<65",
            result));
    assertEquals(64, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT captured, wall FROM zoned_events WHERE id=1", result));
    long updatedCaptured = result.valueAt(0);
    long updatedWall = result.valueAt(1);
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT captured, wall FROM zoned_events WHERE id=64", result));
    assertEquals(updatedCaptured, result.valueAt(0));
    assertEquals(updatedWall, result.valueAt(1));

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT TIMESTAMP '2024-03-31 01:30:00' AT TIME ZONE 'Europe/London'",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT TIMESTAMP '2023-10-29 01:30:00' AT TIME ZONE 'Europe/London'",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT TIMESTAMP '2023-10-29 01:30:00' AT TIME ZONE '+01:00'",
            result));
    long overlapSummer = result.valueAt(0);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT TIMESTAMP '2023-10-29 01:30:00' AT TIME ZONE '+00:00'",
            result));
    assertEquals(3_600_000_000L, result.valueAt(0) - overlapSummer);
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "SELECT TIMESTAMP WITH TIME ZONE '0001-01-01 00:00:00+14:00'",
            result));
    assertEquals(
        StatusCode.INVALID_DATETIME_FORMAT,
        session.execute(
            "SELECT TIMESTAMP WITH TIME ZONE '1970-01-01T00:00:00+00:00'",
            result));

    assertEquals(StatusCode.OK, session.execute("BEGIN", result));
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE '+02:00'", result));
    assertEquals(StatusCode.OK, session.execute("ROLLBACK", result));
    assertEquals(StatusCode.OK, session.execute("SELECT LOCALTIMESTAMP", result));
    assertEquals(SqlTypeDescriptor.timestamp(6), result.typeDescriptorAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO zoned_events (id, captured, wall, day, clock, fixed) "
                + "VALUES (66,DEFAULT,DEFAULT,DEFAULT,DEFAULT,DEFAULT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT captured, wall FROM zoned_events WHERE id=66", result));
    assertEquals(2 * 3_600_000_000L, result.valueAt(1) - result.valueAt(0));
    assertFalse(EmbeddedRiver.timeZoneDatabaseVersion().isBlank());

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "UPDATE zoned_events SET captured=DEFAULT, wall=DEFAULT, "
                + "day=DEFAULT, clock=DEFAULT, fixed=TIMESTAMP WITH TIME ZONE "
                + "'2001-01-01 00:00:00+00:00' WHERE id=1",
            result));
    assertEquals(1, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT captured, wall, day, clock, fixed FROM zoned_events WHERE id=1",
            result));
    long[] replayed = values(result, 5);
    int[] replayedDescriptors = {
        result.typeDescriptorAt(0),
        result.typeDescriptorAt(1),
        result.typeDescriptorAt(2),
        result.typeDescriptorAt(3),
        result.typeDescriptorAt(4)
    };
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), replayedDescriptors[0]);
    assertEquals(SqlTypeDescriptor.timestamp(6), replayedDescriptors[1]);
    assertEquals(SqlTypeDescriptor.DATE, replayedDescriptors[2]);
    assertEquals(SqlTypeDescriptor.time(6), replayedDescriptors[3]);
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), replayedDescriptors[4]);
    assertEquals(978_307_200_000_000L, replayed[4]);
    assertTableCount(
        session,
        result,
        "zoned_events",
        "fixed=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+01:00'",
        64);
    assertEquals(2 * 3_600_000_000L, replayed[1] - replayed[0]);
    assertEquals(
        replayed[1],
        replayed[2] * LocalTemporal.MICROSECONDS_PER_DAY + replayed[3]);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT captured, wall, day, clock, fixed FROM zoned_events WHERE id=1",
            result));
    assertValues(replayed, result);
    for (int index = 0; index < replayedDescriptors.length; index++) {
      assertEquals(replayedDescriptors[index], result.typeDescriptorAt(index));
    }
    assertEquals(978_307_200_000_000L, result.valueAt(4));
    assertTableCount(
        session,
        result,
        "zoned_events",
        "fixed=TIMESTAMP WITH TIME ZONE '2001-01-01 00:00:00+00:00'",
        1);
    assertIndexPlan(
        session,
        "SELECT id FROM zoned_events WHERE fixed="
            + "TIMESTAMP WITH TIME ZONE '2001-01-01 00:00:00+00:00'");
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO zoned_events (id, captured, wall, day, clock, fixed) "
                + "VALUES (65,DEFAULT,DEFAULT,DEFAULT,DEFAULT,DEFAULT)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT captured, wall, day, clock FROM zoned_events WHERE id=65", result));
    assertEquals(result.valueAt(0), result.valueAt(1));
    assertEquals(
        result.valueAt(1),
        result.valueAt(2) * LocalTemporal.MICROSECONDS_PER_DAY + result.valueAt(3));
    assertFalse(result.valueAt(0) == 0);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertTemporalRow(CommandResult result) {
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(0));
    assertEquals(19_782, result.valueAt(0));
    assertEquals(SqlTypeDescriptor.time(3), result.typeDescriptorAt(1));
    assertEquals(3_723_456_000L, result.valueAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(6), result.typeDescriptorAt(2));
    assertEquals(1_709_210_096_123_000L, result.valueAt(2));
  }

  private static long[] values(CommandResult result, int count) {
    long[] values = new long[count];
    for (int index = 0; index < count; index++) {
      values[index] = result.valueAt(index);
    }
    return values;
  }

  private static void assertValues(long[] expected, CommandResult actual) {
    for (int index = 0; index < expected.length; index++) {
      assertEquals(expected[index], actual.valueAt(index));
    }
  }

  private static void assertCount(
      RiverSession session, CommandResult result, String predicate, long expected) {
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM moments WHERE " + predicate, result));
    assertEquals(expected, result.valueAt(0));
  }

  private static void assertTimeCount(
      RiverSession session, CommandResult result, String predicate, long expected) {
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT COUNT(*) FROM schedules WHERE " + predicate, result));
    assertEquals(expected, result.valueAt(0));
  }

  private static void assertTableCount(
      RiverSession session,
      CommandResult result,
      String table,
      String predicate,
      long expected) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM " + table + " WHERE " + predicate, result));
    assertEquals(expected, result.valueAt(0));
  }

  private static void assertPredicateCount(
      RiverSession session, CommandResult result, String predicate, long expected) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM temporal_predicates WHERE " + predicate, result));
    assertEquals(expected, result.valueAt(0));
  }

  private static void assertIndexPlan(RiverSession session, String sql) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery("EXPLAIN " + sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    boolean indexed = false;
    assertEquals(StatusCode.OK, query.next(row));
    while (row.isAvailable()) {
      indexed |= row.valueAt(0) == PackedText.pack("index");
      assertEquals(StatusCode.OK, query.next(row));
    }
    assertEquals(true, indexed);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertOrderedIds(
      RiverSession session, long first, long second) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery("SELECT id FROM wide_scalars ORDER BY id", opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(true, row.isAvailable());
    assertEquals(first, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(true, row.isAvailable());
    assertEquals(second, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }
}
