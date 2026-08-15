package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.text.PackedText;
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

/** Real-path coverage for selected direct-root primitive computed keys. */
final class EmbeddedRiverTemporalComputedKeyTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x54454d504b455953L, 0x3030303030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void ordersDeduplicatesAndGroupsSelectedComputedKeys(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    createFixture(session, result);

    assertComputedOrder(session, result);
    assertComputedDistinctAndGroup(session, result);
    assertAdmissionAndCleanup(session, result);
    assertRawRegressions(session);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void spillsProjectedPrimitiveKeysWithoutLosingTextAssociation(
      @TempDir Path root) {
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
            "CREATE TABLE computed_key_spill (id BIGINT PRIMARY KEY, "
                + "happened TIMESTAMP(6) NOT NULL, label VARCHAR(8) NOT NULL)",
            result));
    insertSpillRows(session, result);

    QueryOpenResult openedQuery = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, CAST(happened AS TIMESTAMP(6)) AS instant, "
                + "CAST(happened AS VARCHAR(26)) AS rendered, label "
                + "FROM computed_key_spill ORDER BY instant",
            openedQuery));
    RiverQuery query = openedQuery.query();
    RowResult row = new RowResult();
    char[] text = new char[32];
    for (int index = 0; index < 1_025; index++) {
      assertEquals(StatusCode.OK, query.next(row));
      long expectedId = index == 0 ? 1 : index == 1 ? 1_025 : index;
      int expectedOffset = index == 0 ? 0 : index == 1 ? 1 : index * 2 - 2;
      assertEquals(expectedId, row.valueAt(0));
      assertEquals(SqlTypeDescriptor.timestamp(6), row.typeDescriptorAt(1));
      if (index < 3 || index == 1_024) {
        int length = row.copyTextAt(2, text, 0);
        assertEquals(timestamp(expectedOffset), new String(text, 0, length));
        length = row.copyTextAt(3, text, 0);
        assertEquals(label(expectedId), new String(text, 0, length));
      }
    }
    assertEnd(query, row, result);
    assertComputedSpillGroups(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createFixture(RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE computed_keys (id BIGINT PRIMARY KEY, bucket BIGINT, "
                + "day DATE, alarm TIME(6), observed TIMESTAMP(6), "
                + "captured TIMESTAMP(6) WITH TIME ZONE, enabled BOOLEAN, "
                + "label VARCHAR(8))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO computed_keys VALUES "
                + "(1,1,DATE '2024-02-28',TIME '01:02:03.100000',"
                + "TIMESTAMP '2024-02-28 10:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:00:00+00:00',TRUE,'one'),"
                + "(2,1,DATE '2024-02-29',TIME '01:02:03.200000',"
                + "TIMESTAMP '2024-02-29 11:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-29 11:00:00+00:00',FALSE,'two'),"
                + "(3,2,DATE '2024-02-28',TIME '01:02:03.100000',"
                + "TIMESTAMP '2024-02-28 12:00:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-28 12:00:00+00:00',TRUE,'three'),"
                + "(4,2,NULL,NULL,NULL,NULL,FALSE,'four'),"
                + "(5,3,DATE '2024-03-31',TIME '01:30:00',"
                + "TIMESTAMP '2024-03-31 01:30:00',"
                + "TIMESTAMP WITH TIME ZONE '2024-03-31 01:30:00+00:00',TRUE,'gap')",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE INDEX computed_keys_day ON computed_keys(day)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE empty_computed_keys "
                + "(id BIGINT PRIMARY KEY, observed TIMESTAMP(6))",
            result));
  }

  private static void assertComputedOrder(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, day+1 AS tomorrow, label FROM computed_keys "
                + "WHERE id<5 ORDER BY tomorrow DESC",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals("tomorrow", query.columnName(1).toString());
    assertOrderRow(query, row, 2, false);
    assertOrderRow(query, row, 3, false);
    assertOrderRow(query, row, 1, false);
    assertOrderRow(query, row, 4, true);
    assertEnd(query, row, result);

    assertPlan(
        session,
        "SELECT id, day+1 AS tomorrow FROM computed_keys WHERE day BETWEEN "
            + "DATE '2024-02-28' AND DATE '2024-02-29' ORDER BY tomorrow",
        "index");
    assertPlan(
        session,
        "SELECT id, day+1 AS tomorrow FROM computed_keys WHERE day BETWEEN "
            + "DATE '2024-02-28' AND DATE '2024-02-29' ORDER BY tomorrow",
        "sort");
  }

  private static void assertComputedDistinctAndGroup(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT DISTINCT day+1 AS tomorrow FROM computed_keys "
                + "WHERE id<5 ORDER BY tomorrow",
            opened));
    RiverQuery distinct = opened.query();
    RowResult row = new RowResult();
    assertValue(distinct, row, 0, true, SqlTypeDescriptor.DATE);
    assertValue(distinct, row, 19_782, false, SqlTypeDescriptor.DATE);
    assertValue(distinct, row, 19_783, false, SqlTypeDescriptor.DATE);
    assertEnd(distinct, row, result);

    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT DISTINCT EXTRACT(SECOND FROM alarm) AS second_value "
                + "FROM computed_keys WHERE id<5 ORDER BY second_value",
            opened));
    distinct = opened.query();
    assertValue(distinct, row, 0, true, SqlTypeDescriptor.decimal(8, 6));
    assertValue(distinct, row, 3_100_000, false, SqlTypeDescriptor.decimal(8, 6));
    assertValue(distinct, row, 3_200_000, false, SqlTypeDescriptor.decimal(8, 6));
    assertEnd(distinct, row, result);

    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT day+1 AS tomorrow, COUNT(*) FROM computed_keys WHERE id<5 "
                + "GROUP BY day+1 ORDER BY tomorrow",
            opened));
    RiverQuery grouped = opened.query();
    assertGroup(grouped, row, 0, true, 1);
    assertGroup(grouped, row, 19_782, false, 2);
    assertGroup(grouped, row, 19_783, false, 1);
    assertEnd(grouped, row, result);

    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT CAST(alarm AS TIME(3)) AS alarm_key, "
                + "MAX(observed AT TIME ZONE 'UTC') FROM computed_keys "
                + "WHERE EXTRACT(DAY FROM day)<31 GROUP BY CAST(alarm AS TIME(3)) "
                + "HAVING MAX(observed AT TIME ZONE 'UTC')>"
                + "TIMESTAMP WITH TIME ZONE '2024-02-28 10:30:00+00:00' "
                + "ORDER BY alarm_key",
            opened));
    RiverQuery aggregate = opened.query();
    assertEquals(StatusCode.OK, aggregate.next(row));
    assertEquals(3_723_100_000L, row.valueAt(0));
    assertEquals(SqlTypeDescriptor.time(3), row.typeDescriptorAt(0));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), row.typeDescriptorAt(1));
    assertEquals(StatusCode.OK, aggregate.next(row));
    assertEquals(3_723_200_000L, row.valueAt(0));
    assertEnd(aggregate, row, result);
  }

  private static void assertAdmissionAndCleanup(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT observed AT TIME ZONE 'Not/A_Real_Zone' AS instant "
                + "FROM empty_computed_keys ORDER BY instant",
            new QueryOpenResult()));
    assertTypedRowCount(
        session,
        "SELECT observed AT TIME ZONE 'Europe/London' AS instant "
            + "FROM computed_keys WHERE EXTRACT(DAY FROM day)<31 ORDER BY instant",
        3,
        SqlTypeDescriptor.timestampWithTimeZone(6));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT observed AT TIME ZONE 'Europe/London' AS instant "
                + "FROM computed_keys ORDER BY instant",
            new QueryOpenResult()));
    assertEquals(StatusCode.OK, session.execute("SELECT id FROM computed_keys WHERE id=1", result));
    assertRowCount(
        session,
        "SELECT day+(CURRENT_DATE-day) AS today FROM computed_keys "
            + "WHERE id<5 ORDER BY today",
        4);
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT CAST(observed AS VARCHAR(26)) AS rendered "
                + "FROM computed_keys ORDER BY rendered",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT CURRENT_DATE AS today FROM computed_keys ORDER BY today",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginQuery(
            "SELECT CAST(enabled AS BOOLEAN) AS flag FROM computed_keys ORDER BY flag",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginQuery(
            "SELECT day+1 AS key_value, alarm AS key_value FROM computed_keys "
                + "ORDER BY key_value",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginQuery(
            "SELECT day+1 AS alarm FROM computed_keys ORDER BY alarm",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.beginQuery(
            "SELECT day+1 FROM computed_keys ORDER BY missing_alias",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT DISTINCT DATE '2024-01-01' AS fixed FROM computed_keys",
            new QueryOpenResult()));
  }

  private static void assertRawRegressions(RiverSession session) {
    assertRowCount(session, "SELECT id FROM computed_keys ORDER BY id DESC", 5);
    assertRowCount(session, "SELECT DISTINCT day FROM computed_keys ORDER BY day", 4);
    assertRowCount(
        session,
        "SELECT bucket, COUNT(*) FROM computed_keys GROUP BY bucket ORDER BY bucket",
        3);
  }

  private static void assertComputedSpillGroups(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT DISTINCT EXTRACT(MINUTE FROM happened) AS minute_value "
                + "FROM computed_key_spill ORDER BY minute_value",
            opened));
    RiverQuery distinct = opened.query();
    RowResult row = new RowResult();
    int distinctRows = drain(distinct, row);
    assertEquals(35, distinctRows);
    assertEquals(StatusCode.OK, distinct.close(result));

    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT EXTRACT(MINUTE FROM happened) AS minute_value, COUNT(*) "
                + "FROM computed_key_spill GROUP BY EXTRACT(MINUTE FROM happened) "
                + "ORDER BY minute_value",
            opened));
    RiverQuery grouped = opened.query();
    long total = 0;
    int groups = 0;
    while (true) {
      assertEquals(StatusCode.OK, grouped.next(row));
      if (!row.isAvailable()) break;
      assertEquals(SqlTypeDescriptor.BIGINT, row.typeDescriptorAt(0));
      total += row.valueAt(1);
      groups++;
    }
    assertEquals(35, groups);
    assertEquals(1_025, total);
    assertEquals(StatusCode.OK, grouped.close(result));
  }

  private static void insertSpillRows(
      RiverSession session, CommandResult result) {
    for (int first = 0; first < 1_025; first += SqlCommand.MAXIMUM_INSERT_ROWS) {
      int last = Math.min(1_025, first + SqlCommand.MAXIMUM_INSERT_ROWS);
      StringBuilder sql = new StringBuilder("INSERT INTO computed_key_spill VALUES ");
      for (int index = first; index < last; index++) {
        if (index > first) sql.append(',');
        int offset = index == 1_024 ? 1 : index * 2;
        long id = index + 1L;
        sql.append('(').append(id).append(",TIMESTAMP '")
            .append(timestamp(offset)).append("','").append(label(id)).append("')");
      }
      assertEquals(StatusCode.OK, session.execute(sql.toString(), result));
    }
  }

  private static String timestamp(int offset) {
    int hour = offset / 3_600;
    int minute = offset / 60 % 60;
    int second = offset % 60;
    return "2024-01-01 " + two(hour) + ':' + two(minute) + ':' + two(second)
        + ".000000";
  }

  private static String two(int value) {
    return value < 10 ? "0" + value : Integer.toString(value);
  }

  private static String label(long id) {
    return "r" + id;
  }

  private static void assertOrderRow(
      RiverQuery query, RowResult row, long id, boolean nullKey) {
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(id, row.valueAt(0));
    assertEquals(nullKey, row.isNull(1));
    assertEquals(SqlTypeDescriptor.DATE, row.typeDescriptorAt(1));
  }

  private static void assertValue(
      RiverQuery query,
      RowResult row,
      long value,
      boolean nullValue,
      int descriptor) {
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(value, row.valueAt(0));
    assertEquals(nullValue, row.isNull(0));
    assertEquals(descriptor, row.typeDescriptorAt(0));
  }

  private static void assertGroup(
      RiverQuery query,
      RowResult row,
      long value,
      boolean nullValue,
      long count) {
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(value, row.valueAt(0));
    assertEquals(nullValue, row.isNull(0));
    assertEquals(SqlTypeDescriptor.DATE, row.typeDescriptorAt(0));
    assertEquals(count, row.valueAt(1));
  }

  private static int drain(RiverQuery query, RowResult row) {
    int count = 0;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) return count;
      count++;
    }
  }

  private static void assertRowCount(
      RiverSession session, String sql, int expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(expected, drain(query, row));
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertTypedRowCount(
      RiverSession session, String sql, int expected, int descriptor) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    int count = 0;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) break;
      assertEquals(descriptor, row.typeDescriptorAt(0));
      count++;
    }
    assertEquals(expected, count);
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertPlan(
      RiverSession session, String sql, String expected) {
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

  private static void assertEnd(
      RiverQuery query, RowResult row, CommandResult result) {
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(result));
  }
}
