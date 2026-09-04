package io.riverdb.engine;

import static io.riverdb.engine.TestDatabaseResources.databaseRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

/** Real-path coverage for computed grouped aggregate operands and HAVING. */
final class EmbeddedRiverTemporalGroupedAggregateExpressionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x54454d5047525045L, 0x5850523030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void groupsComputedOperandsThroughOrderedAndSpilledPaths(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(databaseRequest(8), root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    createFixture(session, result);

    assertOrderedAggregates(session, result);
    assertMaterializedAndSpilledAggregates(session, result);
    assertPostAggregateHaving(session, result);
    assertSharedTemporalSnapshot(session);
    assertBoundariesAndCleanup(session, result);

    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertSharedTemporalSnapshot(RiverSession session) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT category,MAX(CURRENT_TIMESTAMP) FROM grouped_moments "
                + "GROUP BY category "
                + "HAVING MAX(CURRENT_TIMESTAMP)=CURRENT_TIMESTAMP "
                + "ORDER BY category",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    for (long category = 7; category <= 9; category++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(category, row.valueAt(0));
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(false, row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void createFixture(RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE grouped_moments (id BIGINT PRIMARY KEY, "
                + "category BIGINT NOT NULL, bucket BIGINT, day DATE, "
                + "observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO grouped_moments VALUES "
                + "(1,7,1,DATE '2024-02-28',TIMESTAMP '2024-02-28 10:00:00'),"
                + "(2,7,1,DATE '2024-02-29',TIMESTAMP '2024-02-29 11:00:00'),"
                + "(3,8,2,NULL,NULL),"
                + "(4,8,2,DATE '2024-03-31',TIMESTAMP '2024-03-31 01:30:00'),"
                + "(5,9,3,NULL,NULL)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE INDEX grouped_moments_category ON grouped_moments(category)", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE grouped_spill (id BIGINT PRIMARY KEY, category BIGINT, day DATE)",
            result));
    insertSpillRows(session, result);
  }

  private static void assertOrderedAggregates(
      RiverSession session, CommandResult result) {
    assertGroups(
        session,
        "SELECT category, COUNT(EXTRACT(DAY FROM day)) FROM grouped_moments "
            + "GROUP BY category ORDER BY category",
        SqlTypeDescriptor.BIGINT,
        7, 2, 8, 1, 9, 0);
    assertGroups(
        session,
        "SELECT category, SUM(day-DATE '2024-02-27') FROM grouped_moments "
            + "WHERE category<9 GROUP BY category ORDER BY category",
        SqlTypeDescriptor.BIGINT,
        7, 3, 8, 33);
    assertGroups(
        session,
        "SELECT category, AVG(day-DATE '2024-02-27') FROM grouped_moments "
            + "WHERE category<9 GROUP BY category ORDER BY category",
        SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 6),
        7, 1_500_000, 8, 33_000_000);
    assertGroups(
        session,
        "SELECT category, COUNT(day+(CURRENT_DATE-day)) FROM grouped_moments "
            + "GROUP BY category ORDER BY category",
        SqlTypeDescriptor.BIGINT,
        7, 2, 8, 1, 9, 0);

    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM grouped_moments WHERE id=1", result));
    long firstDay = result.valueAt(0);
    assertGroups(
        session,
        "SELECT category, MIN(CAST(day AS TIMESTAMP(3))) FROM grouped_moments "
            + "WHERE category<9 GROUP BY category ORDER BY category",
        SqlTypeDescriptor.timestamp(3),
        7, firstDay * LocalTemporal.MICROSECONDS_PER_DAY,
        8, (firstDay + 32) * LocalTemporal.MICROSECONDS_PER_DAY);
    assertGroups(
        session,
        "SELECT category, MAX(observed AT TIME ZONE 'UTC') FROM grouped_moments "
            + "GROUP BY category HAVING MAX(observed AT TIME ZONE 'UTC')>="
            + "TIMESTAMP WITH TIME ZONE '2024-03-01 00:00:00+00:00' "
            + "ORDER BY category",
        SqlTypeDescriptor.timestampWithTimeZone(6),
        8,
        (firstDay + 32) * LocalTemporal.MICROSECONDS_PER_DAY
            + 90 * 60 * LocalTemporal.MICROSECONDS_PER_SECOND);
    assertGroups(
        session,
        "SELECT category, MAX(observed AT TIME ZONE 'Europe/London') "
            + "FROM grouped_moments WHERE EXTRACT(DAY FROM day)<31 "
            + "GROUP BY category ORDER BY category",
        SqlTypeDescriptor.timestampWithTimeZone(6),
        7,
        (firstDay + 1) * LocalTemporal.MICROSECONDS_PER_DAY
            + 11 * 60 * 60 * LocalTemporal.MICROSECONDS_PER_SECOND);
    assertNullGroup(
        session,
        "SELECT category, MIN(CAST(day AS TIMESTAMP(3))) FROM grouped_moments "
            + "WHERE category=9 GROUP BY category",
        9,
        SqlTypeDescriptor.timestamp(3));
  }

  private static void assertMaterializedAndSpilledAggregates(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM grouped_moments WHERE id=1", result));
    long firstDay = result.valueAt(0);
    assertGroups(
        session,
        "SELECT bucket, MAX(CAST(day AS TIMESTAMP(3))) FROM grouped_moments "
            + "WHERE bucket<3 GROUP BY bucket ORDER BY bucket",
        SqlTypeDescriptor.timestamp(3),
        1, (firstDay + 1) * LocalTemporal.MICROSECONDS_PER_DAY,
        2, (firstDay + 32) * LocalTemporal.MICROSECONDS_PER_DAY);
    assertGroups(
        session,
        "SELECT category, COUNT(EXTRACT(DAY FROM day)) FROM grouped_moments "
            + "WHERE EXTRACT(DAY FROM observed)>=29 GROUP BY category ORDER BY category",
        SqlTypeDescriptor.BIGINT,
        7, 1, 8, 1);
    assertGroups(
        session,
        "SELECT category, SUM(EXTRACT(DAY FROM day)) FROM grouped_spill "
            + "GROUP BY category ORDER BY category",
        SqlTypeDescriptor.BIGINT,
        0, 14_848, 1, 14_877);
  }

  private static void assertBoundariesAndCleanup(
      RiverSession session, CommandResult result) {
    assertTextGroups(
        session,
        "SELECT category, MIN(CAST(day AS VARCHAR(10))) FROM grouped_moments "
            + "GROUP BY category ORDER BY category",
        new long[] {7, 8, 9},
        new String[] {"2024-02-28", "2024-03-31", null});
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM grouped_moments WHERE id=1", result));
    long firstDay = result.valueAt(0);
    assertGroups(
        session,
        "SELECT category, MIN(DATE '2024-01-01') FROM grouped_moments "
            + "GROUP BY category ORDER BY category",
        SqlTypeDescriptor.DATE,
        7, firstDay - 58, 8, firstDay - 58, 9, firstDay - 58);
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginQuery(
            "SELECT category, SUM(CAST(day AS TIMESTAMP(3))) FROM grouped_moments "
                + "GROUP BY category",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.beginQuery(
            "SELECT category, MAX(observed AT TIME ZONE 'UTC') FROM grouped_moments "
                + "GROUP BY category HAVING MAX(observed AT TIME ZONE 'UTC')>="
                + "DATE '2024-01-01'",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT category, MIN(observed AT TIME ZONE 'Not/A_Real_Zone') "
                + "FROM grouped_moments WHERE category=9 GROUP BY category",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO grouped_moments VALUES "
                + "(6,6,0,DATE '2024-02-27',TIMESTAMP '2024-02-27 09:00:00')",
            result));

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT category, MAX(observed AT TIME ZONE 'Europe/London') "
                + "FROM grouped_moments GROUP BY category ORDER BY category",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM grouped_moments WHERE id=1", result));

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT bucket, MAX(observed AT TIME ZONE 'Europe/London') "
                + "FROM grouped_moments GROUP BY bucket",
            new QueryOpenResult()));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM grouped_moments WHERE id=2", result));
  }

  private static void assertPostAggregateHaving(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT day FROM grouped_moments WHERE id=1", result));
    long firstDay = result.valueAt(0);
    assertGroups(
        session,
        "SELECT category, MAX(day) FROM grouped_moments GROUP BY category "
            + "HAVING EXTRACT(DAY FROM MAX(day))>=29 ORDER BY category",
        SqlTypeDescriptor.DATE,
        7, firstDay + 1, 8, firstDay + 32);
    assertGroups(
        session,
        "SELECT day, MAX(day) FROM grouped_moments WHERE day IS NOT NULL "
            + "GROUP BY day HAVING MAX(day)+1>DATE '2024-02-28' ORDER BY day",
        SqlTypeDescriptor.DATE,
        firstDay, firstDay, firstDay + 1, firstDay + 1,
        firstDay + 32, firstDay + 32);
    assertGroups(
        session,
        "SELECT category, MAX(day) FROM grouped_moments GROUP BY category "
            + "HAVING MAX(day)+(CURRENT_DATE-CURRENT_DATE)>="
            + "DATE '2024-02-29' ORDER BY category",
        SqlTypeDescriptor.DATE,
        7, firstDay + 1, 8, firstDay + 32);
    assertGroups(
        session,
        "SELECT category, COUNT(*) FROM grouped_moments GROUP BY category "
            + "HAVING ROUND(ABS(COUNT(*)*2)/2,0)>1 ORDER BY category",
        SqlTypeDescriptor.BIGINT,
        7, 2, 8, 2);
    assertGroups(
        session,
        "SELECT category, AVG(day-DATE '2024-02-27') FROM grouped_moments "
            + "GROUP BY category HAVING ROUND(AVG(day-DATE '2024-02-27'),0)>2 "
            + "ORDER BY category",
        SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 6),
        8, 33_000_000);
    assertGroups(
        session,
        "SELECT category, MAX(day) FROM grouped_moments "
            + "WHERE EXTRACT(DAY FROM observed)>=29 GROUP BY category "
            + "HAVING MAX(day)+1>=DATE '2024-03-01' ORDER BY category",
        SqlTypeDescriptor.DATE,
        7, firstDay + 1, 8, firstDay + 32);
    assertGroups(
        session,
        "SELECT category, SUM(EXTRACT(DAY FROM day)) FROM grouped_spill "
            + "GROUP BY category HAVING SUM(EXTRACT(DAY FROM day))+1>14849 "
            + "ORDER BY category",
        SqlTypeDescriptor.BIGINT,
        1, 14_877);

    QueryOpenResult empty = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT category, MIN(day) FROM grouped_moments WHERE category=9 "
                + "GROUP BY category HAVING MIN(day)+1>DATE '2024-01-01'",
            empty));
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, empty.query().next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, empty.query().close(result));

    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT category, MAX(observed) FROM grouped_moments WHERE category=9 "
                + "GROUP BY category HAVING EXTRACT(HOUR FROM MAX(observed) "
                + "AT TIME ZONE 'Not/A_Real_Zone')=0",
            new QueryOpenResult()));

    QueryOpenResult failed = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT category, MAX(observed) FROM grouped_moments GROUP BY category "
                + "HAVING EXTRACT(HOUR FROM MAX(observed) "
                + "AT TIME ZONE 'Europe/London')>=0 ORDER BY category",
            failed));
    RiverQuery query = failed.query();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(7, row.valueAt(0));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.OK, query.close(result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM grouped_moments WHERE id=1", result));

    assertPostAggregateFailure(
        session,
        "SELECT category, MAX(day) FROM grouped_moments GROUP BY category "
            + "HAVING MAX(day)+9223372036854775807>DATE '2024-01-01'",
        StatusCode.DATETIME_FIELD_OVERFLOW);
    assertPostAggregateFailure(
        session,
        "SELECT category, COUNT(*) FROM grouped_moments GROUP BY category "
            + "HAVING COUNT(*)/0>0",
        StatusCode.DIVISION_BY_ZERO);
  }

  private static void assertPostAggregateFailure(
      RiverSession session, String sql, StatusCode expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened), sql);
    RowResult row = new RowResult();
    assertEquals(expected, opened.query().next(row));
    assertEquals(expected, opened.query().next(row));
    assertEquals(StatusCode.OK, opened.query().close(new CommandResult()));
  }

  private static void insertSpillRows(
      RiverSession session, CommandResult result) {
    for (int first = 1; first <= 1_025; first += SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS) {
      int last = Math.min(1_025, first + SqlCommand.RECOMMENDED_INSERT_BATCH_ROWS - 1);
      StringBuilder sql = new StringBuilder("INSERT INTO grouped_spill VALUES ");
      for (int id = first; id <= last; id++) {
        if (id > first) sql.append(',');
        sql.append('(').append(id).append(',').append(id & 1)
            .append(",DATE '2024-02-29')");
      }
      assertEquals(StatusCode.OK, session.execute(sql.toString(), result));
    }
  }

  private static void assertGroups(
      RiverSession session, String sql, int descriptor, long... expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    for (int index = 0; index < expected.length; index += 2) {
      assertEquals(StatusCode.OK, query.next(row), sql);
      assertEquals(expected[index], row.valueAt(0), sql);
      assertFalse(row.isNull(1));
      assertEquals(expected[index + 1], row.valueAt(1), sql);
      assertEquals(descriptor, row.typeDescriptorAt(1), sql);
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertNullGroup(
      RiverSession session, String sql, long key, int descriptor) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(key, row.valueAt(0));
    assertEquals(true, row.isNull(1));
    assertEquals(descriptor, row.typeDescriptorAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }

  private static void assertTextGroups(
      RiverSession session, String sql, long[] keys, String[] values) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    char[] text = new char[32];
    for (int index = 0; index < keys.length; index++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(keys[index], row.valueAt(0));
      String expected = values[index];
      if (expected == null) {
        assertEquals(true, row.isNull(1));
      } else {
        assertFalse(row.isNull(1));
        int length = row.copyTextAt(1, text, 0);
        assertEquals(expected.length(), length);
        for (int character = 0; character < length; character++) {
          assertEquals(expected.charAt(character), text[character]);
        }
      }
    }
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(new CommandResult()));
  }
}
