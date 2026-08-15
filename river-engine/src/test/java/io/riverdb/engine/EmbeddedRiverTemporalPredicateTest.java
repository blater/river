package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
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

/** Real-path coverage for one direct-root computed temporal predicate. */
final class EmbeddedRiverTemporalPredicateTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x54454d5050524544L, 0x4943415445303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void filtersPointStreamAndOrderedRowsWithStatusTruth(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    createFixture(session, result);

    assertPlan(session,
        "SELECT id FROM events WHERE EXTRACT(DAY FROM day)=28", "table");
    assertPlan(session,
        "SELECT id FROM events WHERE day=DATE '2024-02-28' "
            + "AND EXTRACT(DAY FROM observed)=28",
        "index");
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT id FROM events WHERE day=DATE '2024-02-28' "
                + "AND EXTRACT(DAY FROM observed)=28",
            result));
    assertEquals(1, result.valueAt(0));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute(
            "SELECT id FROM events WHERE day=DATE '2024-02-28' "
                + "AND EXTRACT(DAY FROM observed)=27",
            result));

    assertIds(session, result, "EXTRACT(DAY FROM day)=28", 1);
    assertIds(session, result, "EXTRACT(DAY FROM day)<>28", 2, 3);
    assertIds(session, result, "EXTRACT(DAY FROM day)<29", 1);
    assertIds(session, result, "EXTRACT(DAY FROM day)<=28", 1);
    assertIds(session, result, "EXTRACT(DAY FROM day)>28", 2, 3);
    assertIds(session, result, "EXTRACT(DAY FROM day)>=29", 2, 3);
    assertIds(session, result, "day+1=DATE '2024-02-29'", 1);
    assertIds(session, result, "day+1 IS NULL", 4);
    assertIds(session, result, "day+1 IS NOT NULL", 1, 2, 3);
    assertIds(
        session,
        result,
        "CAST(captured AS VARCHAR(32))="
            + "'2024-02-28 10:00:00.000000+00:00'",
        1);
    assertIds(
        session,
        result,
        "observed AT TIME ZONE 'UTC'="
            + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00'",
        1);
    assertComputedRangesAndMembership(session, result);
    assertGroupedAndDistinctPredicates(session, result);

    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginQuery(
            "SELECT id FROM events WHERE EXTRACT(DAY FROM day)=DATE '2024-02-28'",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT id FROM events WHERE (CURRENT_DATE)=DATE '2024-02-28'",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT COUNT(*) FROM events WHERE EXTRACT(DAY FROM day)=28", result));
    assertEquals(1, result.valueAt(0));
    assertRows(
        session,
        "SELECT DISTINCT EXTRACT(DAY FROM day) FROM events",
        0,
        28,
        29,
        31);
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT events.id FROM events LEFT JOIN empty_times "
                + "ON events.id=empty_times.id "
                + "WHERE EXTRACT(DAY FROM events.day)=28",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE VIEW rejected_temporal_predicate AS SELECT id FROM events "
                + "WHERE EXTRACT(DAY FROM day)=28",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.beginQuery(
            "SELECT id FROM rejected_temporal_predicate",
            new QueryOpenResult()));

    assertInvalidZoneBeforeRows(session, result);
    assertTerminalFailureAndCleanup(session, result);
    assertOrderedFailureCleanup(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertGroupedAndDistinctPredicates(
      RiverSession session, CommandResult result) {
    assertPlan(
        session,
        "SELECT day, COUNT(*) FROM events WHERE day BETWEEN "
            + "DATE '2024-02-28' AND DATE '2024-03-31' "
            + "AND EXTRACT(DAY FROM observed)>=29 GROUP BY day ORDER BY day",
        "index");
    assertPlan(
        session,
        "SELECT day, COUNT(*) FROM events WHERE day BETWEEN "
            + "DATE '2024-02-28' AND DATE '2024-03-31' "
            + "AND EXTRACT(DAY FROM observed)>=29 GROUP BY day ORDER BY day",
        "sort");
    assertEquals(StatusCode.OK, session.execute("SELECT day FROM events WHERE id=2", result));
    long february = result.valueAt(0);
    assertEquals(StatusCode.OK, session.execute("SELECT day FROM events WHERE id=3", result));
    long march = result.valueAt(0);

    assertRows(
        session,
        "SELECT day, COUNT(*) FROM events WHERE day BETWEEN "
            + "DATE '2024-02-28' AND DATE '2024-03-31' "
            + "AND EXTRACT(DAY FROM observed)>=29 GROUP BY day ORDER BY day",
        february,
        march);
    assertRows(
        session,
        "SELECT id, COUNT(*) FROM events WHERE EXTRACT(DAY FROM observed)>=29 "
            + "GROUP BY id ORDER BY id",
        2,
        3);
    assertRows(
        session,
        "SELECT DISTINCT day FROM events WHERE EXTRACT(DAY FROM observed)>=29 "
            + "ORDER BY day",
        february,
        march);
    assertRows(
        session,
        "SELECT DISTINCT id FROM events WHERE EXTRACT(DAY FROM observed)>=29 "
            + "ORDER BY id",
        2,
        3);
    assertRows(
        session,
        "SELECT category, COUNT(*) FROM events "
            + "WHERE EXTRACT(DAY FROM observed)>=29 GROUP BY category ORDER BY category",
        7,
        8);
    assertRows(
        session,
        "SELECT DISTINCT category FROM events "
            + "WHERE EXTRACT(DAY FROM observed)=29 ORDER BY category",
        7);
    assertRows(
        session,
        "SELECT id, COUNT(*) FROM events "
            + "WHERE day+(CURRENT_DATE-day)>DATE '0001-01-01' GROUP BY id ORDER BY id",
        1,
        2,
        3);

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT DISTINCT day FROM events WHERE observed AT TIME ZONE "
                + "'Europe/London'>=TIMESTAMP WITH TIME ZONE "
                + "'0001-01-01 00:00:00+00:00'",
            new QueryOpenResult()));
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, COUNT(*) FROM events WHERE observed AT TIME ZONE "
                + "'Europe/London'>=TIMESTAMP WITH TIME ZONE "
                + "'0001-01-01 00:00:00+00:00' GROUP BY id ORDER BY id",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.OK, query.close(result));
    assertEquals(StatusCode.OK, session.execute("SELECT id FROM events WHERE id=1", result));
    assertSpilledComputedGroup(session, result);
  }

  private static void assertRows(
      RiverSession session, String sql, long... expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    for (long value : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(true, row.isAvailable());
      assertEquals(value, row.valueAt(0));
      if (row.columnCount() > 1) assertEquals(1, row.valueAt(1));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void createFixture(RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE events (id BIGINT PRIMARY KEY, category BIGINT, day DATE, "
                + "alarm TIME(6), observed TIMESTAMP(6), "
                + "captured TIMESTAMP(6) WITH TIME ZONE)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO events VALUES "
                + "(1,7,DATE '2024-02-28',TIME '01:02:03',"
                + "TIMESTAMP '2024-02-28 10:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00'),"
                + "(2,7,DATE '2024-02-29',TIME '01:02:03.123',"
                + "TIMESTAMP '2024-02-29 11:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-29 11:00:00+00:00'),"
                + "(3,8,DATE '2024-03-31',TIME '01:02:03.123456',"
                + "TIMESTAMP '2024-03-31 01:30:00',NULL),"
                + "(4,8,NULL,NULL,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX events_day ON events(day)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE empty_times (id BIGINT PRIMARY KEY, observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("INSERT INTO empty_times VALUES (1,NULL)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_events (id BIGINT PRIMARY KEY, category BIGINT, day DATE)",
            result));
    insertSpillRows(session, result);
  }

  private static void assertComputedRangesAndMembership(
      RiverSession session, CommandResult result) {
    assertIds(
        session,
        result,
        "day+0 BETWEEN DATE '2024-02-28' AND DATE '2024-02-29'",
        1,
        2);
    assertIds(
        session,
        result,
        "CAST(alarm AS TIME(6)) BETWEEN "
            + "TIME '01:02:03' AND TIME '01:02:03.123000'",
        1,
        2);
    assertIds(
        session,
        result,
        "CAST(observed AS TIMESTAMP(6)) IN ("
            + "TIMESTAMP '2024-02-28 10:00:00',"
            + "TIMESTAMP '2024-02-29 11:00:00.000000')",
        1,
        2);
    assertIds(
        session,
        result,
        "CAST(captured AS TIMESTAMP(6) WITH TIME ZONE) IN ("
            + "NULL,TIMESTAMP WITH TIME ZONE "
            + "'2024-02-28 10:00:00+00:00')",
        1);
    assertIds(
        session,
        result,
        "CAST(captured AS TIMESTAMP(6) WITH TIME ZONE) NOT IN ("
            + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00')",
        2);
    assertIds(
        session,
        result,
        "CAST(captured AS TIMESTAMP(6) WITH TIME ZONE) NOT IN ("
            + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00',NULL)");
    assertPlan(
        session,
        "SELECT COUNT(*) FROM events WHERE day=DATE '2024-02-28' "
            + "AND day+0 BETWEEN DATE '2024-02-28' AND DATE '2024-02-29'",
        "index");
    assertPlan(
        session,
        "SELECT COUNT(*) FROM events WHERE day+0 BETWEEN "
            + "DATE '2024-02-28' AND DATE '2024-02-29'",
        "table");
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT id FROM events WHERE CAST(captured AS VARCHAR(32)) IN ("
                + "'2024-02-28 10:00:00.000000+00:00')",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT id FROM events WHERE CAST(captured AS VARCHAR(32)) BETWEEN "
                + "'2024-02-28 00:00:00.000000+00:00' AND "
                + "'2024-02-29 00:00:00.000000+00:00'",
            new QueryOpenResult()));
  }

  private static void insertSpillRows(
      RiverSession session, CommandResult result) {
    for (int first = 1; first <= 1_026; first += SqlCommand.MAXIMUM_INSERT_ROWS) {
      int last = Math.min(1_026, first + SqlCommand.MAXIMUM_INSERT_ROWS - 1);
      StringBuilder sql = new StringBuilder("INSERT INTO spill_events VALUES ");
      for (int id = first; id <= last; id++) {
        if (id > first) sql.append(',');
        sql.append('(').append(id).append(',').append(id & 1).append(",DATE '")
            .append(id == 1 ? "2024-02-28" : "2024-02-29").append("')");
      }
      assertEquals(StatusCode.OK, session.execute(sql.toString(), result));
    }
  }

  private static void assertSpilledComputedGroup(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT category, COUNT(*) FROM spill_events "
                + "WHERE EXTRACT(DAY FROM day)=29 GROUP BY category ORDER BY category",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(0, row.valueAt(0));
    assertEquals(513, row.valueAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(512, row.valueAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(result));
  }

  private static void assertInvalidZoneBeforeRows(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT id FROM empty_times "
                + "WHERE observed AT TIME ZONE 'Not/A_Real_Zone' IS NULL",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT DISTINCT observed FROM empty_times "
                + "WHERE observed AT TIME ZONE 'Not/A_Real_Zone' IS NULL",
            new QueryOpenResult()));
    assertEquals(StatusCode.OK, session.execute("SELECT id FROM events WHERE id=1", result));
  }

  private static void assertTerminalFailureAndCleanup(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM events WHERE observed AT TIME ZONE 'Europe/London'>="
                + "TIMESTAMP WITH TIME ZONE '0001-01-01 00:00:00+00:00' ORDER BY id",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(2, row.valueAt(0));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.OK, query.close(result));
    assertEquals(StatusCode.OK, session.execute("SELECT id FROM events WHERE id=1", result));
  }

  private static void assertOrderedFailureCleanup(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT id FROM events WHERE observed AT TIME ZONE 'Europe/London'>="
                + "TIMESTAMP WITH TIME ZONE '0001-01-01 00:00:00+00:00' "
                + "ORDER BY day DESC",
            new QueryOpenResult()));
    assertEquals(StatusCode.OK, session.execute("SELECT id FROM events WHERE id=2", result));
  }

  private static void assertIds(
      RiverSession session, CommandResult result, String predicate, long... expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM events WHERE " + predicate + " ORDER BY id", opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    for (long id : expected) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(true, row.isAvailable());
      assertEquals(id, row.valueAt(0));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(result));
  }

  private static void assertPlan(RiverSession session, String sql, String expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery("EXPLAIN " + sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    boolean found = false;
    assertEquals(StatusCode.OK, query.next(row));
    while (row.isAvailable()) {
      found |= row.valueAt(0) == PackedText.pack(expected);
      assertEquals(StatusCode.OK, query.next(row));
    }
    assertEquals(true, found);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }
}
