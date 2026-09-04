package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
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
import io.riverdb.sql.SqlCommand;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end context coverage for primitive temporal values in relational operators. */
final class EmbeddedRiverTemporalContextTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x54454d5043545831L, 0x52415756414c3031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);
  private static final long FEBRUARY_29_2024 = 19_782;
  private static final long MARCH_1_2024 = 19_783;
  private static final long JANUARY_1_2024_MICROS = 1_704_067_200_000_000L;
  private static final int SPILL_ROWS = 1_025;

  @Test
  void preservesRawTemporalSemanticsAcrossRelationalContextsAndReopen(
      @TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();

    createContextTables(session, result);
    insertContextRows(session, result);
    assertPointAndStreamingProjection(session, result);
    assertMixedPrecisionJoins(session, result);
    assertDistinctAggregatesAndOrdering(session, result);
    assertViewAndCorrelatedPredicate(session, result);

    assertEquals(StatusCode.OK, session.execute("CHECKPOINT", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());

    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertReopenedContext(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void spillsAndMergesUnindexedLocalAndInstantOrdering(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE temporal_spill (id BIGINT PRIMARY KEY, "
                + "local_seen TIMESTAMP(6) NOT NULL, "
                + "captured TIMESTAMP(6) WITH TIME ZONE NOT NULL)",
            result));

    insertSpillRows(session, result);
    assertLocalSpillOrder(session, result);
    assertInstantSpillOrder(session, result);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createContextTables(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE temporal_left (id BIGINT PRIMARY KEY, day DATE, "
                + "clock TIME(3), local_seen TIMESTAMP(3), "
                + "captured TIMESTAMP(6) WITH TIME ZONE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE temporal_right (id BIGINT PRIMARY KEY, day DATE, "
                + "clock TIME(6), local_seen TIMESTAMP(6), "
                + "captured TIMESTAMP(3) WITH TIME ZONE)",
            result));
  }

  private static void insertContextRows(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO temporal_left VALUES "
                + "(1,DATE '2024-02-29',TIME '01:02:03.100',"
                + "TIMESTAMP '2024-02-29 10:00:00.100',"
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 01:00:00+01:00'),"
                + "(2,DATE '2024-02-29',TIME '01:02:03.123',"
                + "TIMESTAMP '2024-02-29 10:00:00.123',"
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00.123+00:00'),"
                + "(3,DATE '2024-03-01',NULL,"
                + "TIMESTAMP '2024-03-01 00:00:00',NULL),"
                + "(4,NULL,NULL,NULL,NULL)",
            result));
    assertEquals(4, result.affectedRows());
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO temporal_right VALUES "
                + "(1,DATE '2024-02-29',TIME '01:02:03.100000',"
                + "TIMESTAMP '2024-02-29 10:00:00.100000',"
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 00:00:00+00:00'),"
                + "(2,DATE '2024-02-29',TIME '01:02:03.123000',"
                + "TIMESTAMP '2024-02-29 10:00:00.123000',"
                + "TIMESTAMP WITH TIME ZONE '2024-01-01 01:00:00.123+01:00'),"
                + "(3,DATE '2024-03-01',TIME '23:59:59.999999',"
                + "TIMESTAMP '2024-03-01 00:00:00.000000',"
                + "TIMESTAMP WITH TIME ZONE '2024-03-01 23:59:59.999+00:00')",
            result));
    assertEquals(3, result.affectedRows());
  }

  private static void assertPointAndStreamingProjection(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT day, clock, local_seen, captured FROM temporal_left WHERE id=4",
            result));
    assertEquals(4, result.columnCount());
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.time(3), result.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(3), result.typeDescriptorAt(2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(3));
    assertEquals(0xfL, result.nullMask());

    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT day, clock, local_seen, captured FROM temporal_left "
                + "WHERE id>=1 AND id<3 ORDER BY id",
            opened));
    RiverQuery query = opened.query();
    assertProjectionTypes(query);
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(FEBRUARY_29_2024, row.valueAt(0));
    assertEquals(3_723_100_000L, row.valueAt(1));
    assertEquals(JANUARY_1_2024_MICROS, row.valueAt(3));
    assertEquals(0, row.nullMask());
    assertProjectionTypes(row);
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(3_723_123_000L, row.valueAt(1));
    assertEquals(JANUARY_1_2024_MICROS + 123_000, row.valueAt(3));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(result));
  }

  private static void assertMixedPrecisionJoins(
      RiverSession session, CommandResult result) {
    assertJoinedIds(
        session,
        result,
        "SELECT l.id, r.id FROM temporal_left l JOIN temporal_right r "
            + "ON l.clock=r.clock",
        1,
        2);
    assertJoinedIds(
        session,
        result,
        "SELECT l.id, r.id FROM temporal_left l JOIN temporal_right r "
            + "ON l.local_seen=r.local_seen",
        1,
        2,
        3);
    assertJoinedIds(
        session,
        result,
        "SELECT l.id, r.id FROM temporal_left l JOIN temporal_right r "
            + "ON l.captured=r.captured",
        1,
        2);
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginQuery(
            "SELECT l.id, r.id FROM temporal_left l JOIN temporal_right r "
                + "ON l.day=r.clock",
            new QueryOpenResult()));
  }

  private static void assertDistinctAggregatesAndOrdering(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT DISTINCT day FROM temporal_left ORDER BY day", opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(true, row.isNull(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(FEBRUARY_29_2024, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(MARCH_1_2024, row.valueAt(0));
    assertEnd(query, row, result);

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MIN(day) FROM temporal_left", result));
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(0));
    assertEquals(FEBRUARY_29_2024, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MAX(local_seen) FROM temporal_left", result));
    assertEquals(SqlTypeDescriptor.timestamp(3), result.typeDescriptorAt(0));
    assertEquals(
        MARCH_1_2024 * LocalTemporal.MICROSECONDS_PER_DAY,
        result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MIN(captured) FROM temporal_left", result));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(0));
    assertEquals(JANUARY_1_2024_MICROS, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT MAX(clock) FROM temporal_left", result));
    assertEquals(SqlTypeDescriptor.time(3), result.typeDescriptorAt(0));
    assertEquals(3_723_123_000L, result.valueAt(0));

    assertGroupedCounts(session, result);
    assertTemporalHaving(session, result);
    assertNullAndDescendingOrder(session, result);
  }

  private static void assertGroupedCounts(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT day, COUNT(*) FROM temporal_left GROUP BY day ORDER BY day",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(true, row.isNull(0));
    assertEquals(1, row.valueAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(FEBRUARY_29_2024, row.valueAt(0));
    assertEquals(2, row.valueAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(MARCH_1_2024, row.valueAt(0));
    assertEquals(1, row.valueAt(1));
    assertEnd(query, row, result);
  }

  private static void assertTemporalHaving(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT day, MAX(local_seen) FROM temporal_left GROUP BY day "
                + "HAVING MAX(local_seen)>=TIMESTAMP '2024-03-01 00:00:00'",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(MARCH_1_2024, row.valueAt(0));
    assertEquals(
        MARCH_1_2024 * LocalTemporal.MICROSECONDS_PER_DAY,
        row.valueAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(3), row.typeDescriptorAt(1));
    assertEnd(query, row, result);
  }

  private static void assertNullAndDescendingOrder(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, local_seen FROM temporal_left ORDER BY local_seen DESC",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    long[] expected = {3, 2, 1, 4};
    for (long id : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(id, row.valueAt(0));
      assertEquals(id == 4, row.isNull(1));
    }
    assertEnd(query, row, result);
  }

  private static void assertViewAndCorrelatedPredicate(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW current_temporal AS SELECT id, day, captured "
                + "FROM temporal_left WHERE day>=DATE '2024-02-29'",
            result));
    assertViewCaptured(session, result, 1, JANUARY_1_2024_MICROS);

    QueryOpenResult derived = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT d.captured FROM (SELECT id, captured FROM temporal_left "
                + "WHERE day=DATE '2024-02-29') d WHERE d.id=2",
            derived));
    RiverQuery derivedQuery = derived.query();
    RowResult derivedRow = new RowResult();
    assertEquals(StatusCode.OK, derivedQuery.next(derivedRow));
    assertEquals(JANUARY_1_2024_MICROS + 123_000, derivedRow.valueAt(0));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6),
        derivedRow.typeDescriptorAt(0));
    assertEnd(derivedQuery, derivedRow, result);

    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT l.id FROM temporal_left l WHERE l.captured IN "
                + "(SELECT r.captured FROM temporal_right r WHERE r.id=l.id)",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(2, row.valueAt(0));
    assertEnd(query, row, result);
  }

  private static void assertReopenedContext(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT day, clock, local_seen, captured FROM temporal_left WHERE id=1",
            result));
    assertEquals(FEBRUARY_29_2024, result.valueAt(0));
    assertEquals(3_723_100_000L, result.valueAt(1));
    assertEquals(JANUARY_1_2024_MICROS, result.valueAt(3));
    assertProjectionTypes(result);
    assertViewCaptured(
        session, result, 2, JANUARY_1_2024_MICROS + 123_000);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT day, clock, local_seen, captured FROM temporal_left WHERE id=4",
            result));
    assertProjectionTypes(result);
    assertEquals(0xfL, result.nullMask());
  }

  private static void assertViewCaptured(
      RiverSession session,
      CommandResult result,
      long id,
      long expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT captured FROM current_temporal WHERE id=" + id, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(expected, row.valueAt(0));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), row.typeDescriptorAt(0));
    assertEnd(query, row, result);
  }

  private static void insertSpillRows(
      RiverSession session, CommandResult result) {
    for (int first = 1; first <= SPILL_ROWS; first += SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS) {
      int last = Math.min(SPILL_ROWS, first + SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS - 1);
      StringBuilder insert = new StringBuilder("INSERT INTO temporal_spill VALUES ");
      for (int id = first; id <= last; id++) {
        if (id > first) {
          insert.append(',');
        }
        int seconds = SPILL_ROWS - id;
        insert.append('(').append(id).append(',');
        appendTimestamp(insert, "TIMESTAMP '", 0, seconds, "',");
        appendTimestamp(
            insert,
            "TIMESTAMP WITH TIME ZONE '",
            1,
            seconds,
            "+01:00')");
      }
      assertEquals(StatusCode.OK, session.execute(insert.toString(), result));
      assertEquals(last - first + 1, result.affectedRows());
    }
  }

  private static void appendTimestamp(
      StringBuilder sql, String prefix, int hour, int seconds, String suffix) {
    int minute = seconds / 60;
    int second = seconds % 60;
    sql.append(prefix).append("2024-01-01 ");
    appendTwoDigits(sql, hour);
    sql.append(':');
    appendTwoDigits(sql, minute);
    sql.append(':');
    appendTwoDigits(sql, second);
    sql.append(".000000").append(suffix);
  }

  private static void appendTwoDigits(StringBuilder sql, int value) {
    sql.append((char) ('0' + value / 10));
    sql.append((char) ('0' + value % 10));
  }

  private static void assertLocalSpillOrder(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, local_seen FROM temporal_spill ORDER BY local_seen",
            opened));
    RiverQuery query = opened.query();
    assertEquals(SqlTypeDescriptor.timestamp(6), query.columnTypeDescriptor(1));
    RowResult row = new RowResult();
    for (int offset = 0; offset < SPILL_ROWS; offset++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(SPILL_ROWS - offset, row.valueAt(0));
      assertEquals(
          JANUARY_1_2024_MICROS + offset * LocalTemporal.MICROSECONDS_PER_SECOND,
          row.valueAt(1));
    }
    assertEnd(query, row, result);
  }

  private static void assertInstantSpillOrder(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, captured FROM temporal_spill "
                + "ORDER BY captured DESC LIMIT 3",
            opened));
    RiverQuery query = opened.query();
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6),
        query.columnTypeDescriptor(1));
    RowResult row = new RowResult();
    for (int offset = 0; offset < 3; offset++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(1 + offset, row.valueAt(0));
      assertEquals(
          JANUARY_1_2024_MICROS
              + (SPILL_ROWS - 1L - offset) * LocalTemporal.MICROSECONDS_PER_SECOND,
          row.valueAt(1));
    }
    assertEnd(query, row, result);
  }

  private static void assertJoinedIds(
      RiverSession session,
      CommandResult result,
      String sql,
      long... expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    for (long id : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(id, row.valueAt(0));
      assertEquals(id, row.valueAt(1));
    }
    assertEnd(query, row, result);
  }

  private static void assertProjectionTypes(RiverQuery query) {
    assertEquals(SqlTypeDescriptor.DATE, query.columnTypeDescriptor(0));
    assertEquals(SqlTypeDescriptor.time(3), query.columnTypeDescriptor(1));
    assertEquals(SqlTypeDescriptor.timestamp(3), query.columnTypeDescriptor(2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6),
        query.columnTypeDescriptor(3));
  }

  private static void assertProjectionTypes(RowResult row) {
    assertEquals(SqlTypeDescriptor.DATE, row.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.time(3), row.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(3), row.typeDescriptorAt(2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), row.typeDescriptorAt(3));
  }

  private static void assertProjectionTypes(CommandResult result) {
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.time(3), result.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.timestamp(3), result.typeDescriptorAt(2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(3));
  }

  private static void assertEnd(
      RiverQuery query, RowResult row, CommandResult result) {
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(result));
  }
}
