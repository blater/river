package io.riverdb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Real-path coverage for bounded direct-table temporal row projections. */
final class EmbeddedRiverTemporalProjectionTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x54454d5050524f4aL, 0x4543543030303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void sessionCloseReleasesQueryAfterDeliveredStreamingFailure(@TempDir Path root) {
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
            "CREATE TABLE close_temporal "
                + "(id BIGINT PRIMARY KEY,observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO close_temporal VALUES "
                + "(1,TIMESTAMP '2024-01-01 00:00:00'),"
                + "(2,TIMESTAMP '0001-01-01 00:00:00')",
            result));
    String failingOrder = "SELECT id FROM close_temporal WHERE observed AT TIME ZONE "
        + "'Europe/London'>=TIMESTAMP WITH TIME ZONE "
        + "'0001-01-01 00:00:00+00:00' ORDER BY observed";
    QueryOpenResult explained = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery("EXPLAIN " + failingOrder, explained));
    RiverQuery explain = explained.query();
    RowResult explainRow = new RowResult();
    do {
      assertEquals(StatusCode.OK, explain.next(explainRow));
    } while (explainRow.isAvailable());
    assertEquals(StatusCode.OK, explain.close(result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery("EXPLAIN ANALYZE " + failingOrder, new QueryOpenResult()));
    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM close_temporal WHERE observed AT TIME ZONE "
                + "'Europe/London'>=TIMESTAMP WITH TIME ZONE "
                + "'0001-01-01 00:00:00+00:00'",
            queryResult));
    RiverQuery query = queryResult.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));

    assertEquals(StatusCode.OK, session.close());
    assertFalse(query.isActive());
    assertEquals(StatusCode.OK, database.close());
  }

  @Test
  void projectsTemporalProgramsThroughPointAndStreaming(@TempDir Path root) {
    DatabaseOpenResult opened = new DatabaseOpenResult();
    assertEquals(StatusCode.OK, EmbeddedRiver.create(root, DATABASE, GENERATION, 8, opened));
    RiverDatabase database = opened.database();
    SessionOpenResult sessionResult = new SessionOpenResult();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    RiverSession session = sessionResult.session();
    CommandResult result = new CommandResult();
    createMoments(session, result);

    String projections = "id AS event_id, EXTRACT(YEAR FROM observed) AS seen_year, "
        + "day+1 AS tomorrow, day-day AS age, "
        + "CAST(observed AS VARCHAR(26)) AS rendered, NULL AS absent, "
        + "CAST(NULL AS TIMESTAMP(3)) AS typed_absent, day+NULL AS null_day";
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT " + projections + " FROM moments WHERE id=1", result));
    assertProjection(result);

    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT " + projections + " FROM moments ORDER BY day", queryResult));
    RiverQuery query = queryResult.query();
    assertEquals("event_id", query.columnName(0).toString());
    assertEquals("rendered", query.columnName(4).toString());
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(2, row.valueAt(0));
    assertEquals(0xfeL, row.nullMask());
    assertEquals(StatusCode.OK, query.next(row));
    assertProjection(row);
    assertEnd(query, row, result);

    assertRowCount(
        session,
        result,
        "SELECT EXTRACT(YEAR FROM observed) AS y FROM moments ORDER BY y",
        2);
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery("SELECT 'too broad' FROM moments", queryResult));
    assertRowCount(session, result, "SELECT id,id FROM moments", 2);
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id+1 FROM moments WHERE id=1", result));
    assertEquals(2, result.valueAt(0));
    assertFalse(result.isNull(0));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id+NULL FROM moments WHERE id=1", result));
    assertTrue(result.isNull(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW temporal_projection AS "
                + "SELECT id,y,tomorrow,rendered,instant,snapshot,absent FROM ("
                + "SELECT id, EXTRACT(YEAR FROM observed) AS y, "
                + "day+1 AS tomorrow, "
                + "CAST(observed AS VARCHAR(26)) AS rendered, "
                + "observed AT TIME ZONE '+02:00' AS instant, "
                + "CURRENT_DATE+id AS snapshot, day+NULL AS absent "
                + "FROM moments) composed",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT y,tomorrow,rendered,instant,absent "
                + "FROM temporal_projection WHERE id=1",
            result));
    assertEquals(2024, result.valueAt(0));
    assertEquals(19_783, result.valueAt(1));
    assertEquals("2024-02-29 10:00:00.123456", text(result, 2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(3));
    long viewInstant = result.valueAt(3);
    assertEquals(true, result.isNull(4));
    assertEquals(
        StatusCode.OK,
        session.execute(
            " \n SeLeCt y FROM temporal_projection WHERE id=1", result));
    assertEquals(2024, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT observed AT TIME ZONE 'UTC' FROM moments WHERE id=1", result));
    assertEquals(result.valueAt(0) - 7_200_000_000L, viewInstant);
    assertStableViewCurrent(session, result);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT EXTRACT(DAY FROM final_day) FROM "
                + "(SELECT shifted+1 AS final_day FROM "
                + "(SELECT day+1 AS shifted FROM moments WHERE id=1) first) second",
            result));
    assertEquals(2, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT y FROM temporal_projection WHERE y=2024 AND id=1", result));
    assertEquals(2024, result.valueAt(0));
    assertComputedDerivedOrder(session, result);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT EXTRACT(DAY FROM parsed) FROM "
                + "(SELECT CAST('2024-02-29' AS DATE)+(day-day) AS parsed "
                + "FROM moments WHERE id=1) q",
            result));
    assertEquals(29, result.valueAt(0));
    assertRowCount(
        session,
        result,
        "SELECT id FROM (SELECT id,source_text FROM moments "
            + "WHERE source_text BETWEEN '2024-01-01' AND '2024-12-31' "
            + "AND source_text IN ('2024-02-29',NULL)) q "
            + "WHERE source_text='2024-02-29'",
        1);
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginQuery(
            "SELECT id FROM temporal_projection WHERE id IN "
                + "(SELECT id FROM moments)",
            queryResult));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT id FROM moments WHERE id IN (SELECT id FROM moments)",
            result));
    assertEquals(1, result.valueAt(0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "CREATE VIEW duplicate_temporal_outputs AS "
                + "SELECT EXTRACT(DAY FROM day) AS d,day+1 AS d FROM moments",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.beginQuery("SELECT d FROM duplicate_temporal_outputs", queryResult));
    assertEquals(
        StatusCode.DATATYPE_MISMATCH,
        session.execute(
            "CREATE VIEW invalid_temporal_expression AS "
                + "SELECT EXTRACT(DAY FROM id) AS bad FROM moments",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.beginQuery("SELECT bad FROM invalid_temporal_expression", queryResult));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW named_temporal_columns AS SELECT id,day FROM moments",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW deep_temporal_projection AS " + nestedView(31), result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT id FROM deep_temporal_projection WHERE id=1", result));
    assertEquals(
        StatusCode.QUERY_TOO_COMPLEX,
        session.execute(
            "CREATE VIEW unusable_deep_projection AS " + nestedView(32), result));
    assertEquals(
        StatusCode.CONFLICT,
        session.beginQuery("SELECT id FROM unusable_deep_projection", queryResult));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE VIEW explained_projection AS "
                + "EXPLAIN SELECT id FROM moments",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "CREATE VIEW invalid_zone_projection AS "
                + "SELECT observed AT TIME ZONE 'Not/A_Real_Zone' AS captured "
                + "FROM moments",
            result));
    assertEquals(
        StatusCode.CONFLICT,
        session.beginQuery("SELECT captured FROM invalid_zone_projection", queryResult));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        session.execute(
            "CREATE VIEW unnamed_temporal_projection AS "
                + "SELECT EXTRACT(DAY FROM day) FROM moments",
            result));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.execute(
            "CREATE VIEW nested_temporal_projection AS "
                + "SELECT id FROM moments WHERE id IN (SELECT id FROM moments)",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW joined_temporal_projection AS "
                + "SELECT EXTRACT(DAY FROM left_side.day) AS d "
                + "FROM moments left_side JOIN moments right_side "
                + "ON left_side.id=right_side.id",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT d FROM joined_temporal_projection WHERE d=29", result));
    assertEquals(29, result.valueAt(0));
    assertRowCount(
        session,
        result,
        "SELECT DISTINCT day FROM moments ORDER BY day",
        2);
    assertRowCount(
        session,
        result,
        "SELECT day, COUNT(*) FROM moments GROUP BY day ORDER BY day",
        2);
    assertStableCurrentProjection(session, result);
    assertTemporalConversions(session, result);
    assertComposedViewPredicates(session, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
    opened.reset();
    assertEquals(
        StatusCode.OK,
        EmbeddedRiver.openExisting(root, DATABASE, GENERATION, 8, opened));
    database = opened.database();
    sessionResult.reset();
    assertEquals(StatusCode.OK, database.createSession(sessionResult));
    session = sessionResult.session();
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT y,tomorrow,rendered,instant,absent "
                + "FROM temporal_projection WHERE id=1",
            result));
    assertEquals(2024, result.valueAt(0));
    assertEquals(19_783, result.valueAt(1));
    assertEquals("2024-02-29 10:00:00.123456", text(result, 2));
    assertEquals(
        SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(3));
    assertEquals(true, result.isNull(4));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT id FROM deep_temporal_projection WHERE id=1", result));
    assertEquals(1, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT y FROM temporal_projection WHERE y=2024 AND id=1", result));
    assertEquals(2024, result.valueAt(0));
    assertRowCount(
        session,
        result,
        "SELECT id FROM block_predicate ORDER BY id",
        1);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void assertStableViewCurrent(
      RiverSession session, CommandResult result) {
    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id,snapshot FROM temporal_projection ORDER BY id", queryResult));
    RiverQuery query = queryResult.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    long snapshot = row.valueAt(1) - row.valueAt(0);
    assertEquals(SqlTypeDescriptor.DATE, row.typeDescriptorAt(1));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(snapshot, row.valueAt(1) - row.valueAt(0));
    assertEnd(query, row, result);
  }

  private static void assertComputedDerivedOrder(
      RiverSession session, CommandResult result) {
    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT d FROM (SELECT EXTRACT(DAY FROM day) AS d FROM moments) q "
                + "ORDER BY d",
            queryResult));
    RiverQuery query = queryResult.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(true, row.isNull(0));
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(29, row.valueAt(0));
    assertEnd(query, row, result);
  }

  private static String nestedView(int blocks) {
    String query = "SELECT id FROM moments";
    for (int depth = 1; depth < blocks; depth++) {
      query = "SELECT id FROM (" + query + ")x";
    }
    return query;
  }

  @Test
  void retainsGeneratedTextAcrossSortSpill(@TempDir Path root) {
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
            "CREATE TABLE projection_spill (id BIGINT PRIMARY KEY, "
                + "happened TIMESTAMP(6) NOT NULL)", result));
    insertSpillRows(session, result);

    QueryOpenResult queryResult = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id, EXTRACT(DAY FROM happened) AS d, "
                + "CAST(happened AS VARCHAR(26)) AS rendered, "
                + "CAST(NULL AS VARCHAR(26)) AS absent "
                + "FROM projection_spill ORDER BY happened",
            queryResult));
    RiverQuery query = queryResult.query();
    RowResult row = new RowResult();
    char[] text = new char[32];
    for (int index = 0; index < 1_025; index++) {
      assertEquals(StatusCode.OK, query.next(row));
      long expectedId = index == 0 ? 1 : index == 1 ? 1_025 : index;
      int expectedOffset = index == 0 ? 0 : index == 1 ? 1 : index * 2 - 2;
      assertEquals(expectedId, row.valueAt(0));
      assertEquals(1, row.valueAt(1));
      assertEquals(true, row.isNull(3));
      if (index < 3 || index == 1_024) {
        int length = row.copyTextAt(2, text, 0);
        assertEquals(26, length);
        assertEquals(timestamp(expectedOffset), new String(text, 0, length));
      }
    }
    assertEnd(query, row, result);
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, database.close());
  }

  private static void createMoments(RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE moments (id BIGINT PRIMARY KEY, day DATE, "
                + "observed TIMESTAMP(6), captured TIMESTAMP(6) WITH TIME ZONE, "
                + "source_text VARCHAR(32))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO moments VALUES "
                + "(1,DATE '2024-02-29',TIMESTAMP '2024-02-29 10:00:00.123456',"
                + "TIMESTAMP WITH TIME ZONE '2024-02-29 10:00:00.123456+00:00',"
                + "'2024-02-29'),"
                + "(2,NULL,NULL,NULL,NULL)",
            result));
  }

  private static void assertComposedViewPredicates(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW raw_temporal_projection AS "
                + "SELECT id,day,observed FROM moments WHERE id>=1",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute("CREATE UNIQUE INDEX moments_day ON moments(day)", result));
    String safeLondon = "SELECT id FROM (SELECT id,observed FROM moments WHERE "
        + "day=DATE '2024-02-29') q "
        + "WHERE observed AT TIME ZONE 'Europe/London'>="
        + "TIMESTAMP WITH TIME ZONE '0001-01-01 00:00:00+00:00'";
    assertPlan(session, safeLondon, "index");
    assertEquals(StatusCode.OK, session.execute(safeLondon, result));
    assertEquals(1, result.valueAt(0));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT id FROM temporal_projection WHERE id=1 AND "
                + "rendered='2024-02-29 10:00:00.123456'",
            result));
    assertEquals(1, result.valueAt(0));
    assertRowCount(
        session,
        result,
        "SELECT id FROM temporal_projection WHERE absent IS NULL ORDER BY id",
        7);
    assertRowCount(
        session,
        result,
        "SELECT id FROM temporal_projection WHERE tomorrow NOT IN "
            + "(DATE '2024-03-01',NULL)",
        0);
    assertRowCount(
        session,
        result,
        "SELECT id,tomorrow FROM temporal_projection WHERE "
            + "tomorrow>=DATE '2024-01-01' ORDER BY tomorrow",
        4);
    assertStablePredicateCurrent(session, result);
    assertInvalidComposedZone(session, result);
    assertComposedPredicateFailure(session, result);
    assertRowCount(
        session,
        result,
        "SELECT id FROM temporal_projection WHERE rendered BETWEEN "
            + "'2024-01-01' AND '2024-12-31'",
        4);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW block_predicate AS SELECT id FROM moments "
                + "WHERE EXTRACT(DAY FROM day)=29",
            result));
    assertRowCount(session, result, "SELECT id FROM block_predicate", 1);
    assertRowCount(
        session,
        result,
        "SELECT id FROM (SELECT id FROM moments WHERE "
            + "EXTRACT(DAY FROM day)=29) q",
        1);
  }

  private static void assertStablePredicateCurrent(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id,snapshot FROM temporal_projection WHERE "
                + "snapshot>=DATE '0001-01-01' ORDER BY id",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    long snapshot = row.valueAt(1) - row.valueAt(0);
    for (int index = 1; index < 7; index++) {
      assertEquals(StatusCode.OK, query.next(row));
      assertEquals(snapshot, row.valueAt(1) - row.valueAt(0));
    }
    assertEnd(query, row, result);
  }

  private static void assertInvalidComposedZone(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE empty_temporal_projection "
                + "(id BIGINT PRIMARY KEY,observed TIMESTAMP(6))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW empty_temporal_view AS "
                + "SELECT id,observed FROM empty_temporal_projection",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT id FROM empty_temporal_view WHERE observed AT TIME ZONE "
                + "'Not/A_Real_Zone'>=TIMESTAMP WITH TIME ZONE "
                + "'0001-01-01 00:00:00+00:00'",
            new QueryOpenResult()));
  }

  private static void assertComposedPredicateFailure(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT id FROM raw_temporal_projection WHERE observed AT TIME ZONE "
                + "'Europe/London'>=TIMESTAMP WITH TIME ZONE "
                + "'0001-01-01 00:00:00+00:00'",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(1, row.valueAt(0));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.INVALID_TIME_ZONE_DISPLACEMENT, query.next(row));
    assertEquals(StatusCode.OK, query.close(result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM moments WHERE id=1", result));
  }

  private static void assertProjection(CommandResult result) {
    assertEquals(8, result.columnCount());
    assertEquals(1, result.valueAt(0));
    assertEquals(2024, result.valueAt(1));
    assertEquals(19_783, result.valueAt(2));
    assertEquals(0, result.valueAt(3));
    assertEquals(SqlTypeDescriptor.varchar(26), result.typeDescriptorAt(4));
    assertEquals("2024-02-29 10:00:00.123456", text(result, 4));
    assertEquals(true, result.isNull(5));
    assertEquals(SqlTypeDescriptor.timestamp(3), result.typeDescriptorAt(6));
    assertEquals(true, result.isNull(6));
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(7));
    assertEquals(true, result.isNull(7));
  }

  private static void assertTemporalConversions(
      RiverSession session, CommandResult result) {
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT observed AT TIME ZONE 'UTC' AS instant, "
                + "captured AT TIME ZONE 'UTC' AS wall, "
                + "EXTRACT(HOUR FROM observed AT TIME ZONE 'UTC') AS hour, "
                + "EXTRACT(SECOND FROM observed) AS second, "
                + "CAST(day AS TIMESTAMP(3)) AS midnight, CURRENT_DATE AS today "
                + "FROM moments WHERE id=1",
            result));
    assertEquals(result.valueAt(0), result.valueAt(1));
    assertEquals(10, result.valueAt(2));
    assertEquals(123_456, result.valueAt(3));
    assertEquals(SqlTypeDescriptor.decimal(8, 6), result.typeDescriptorAt(3));
    assertEquals(19_782L * 86_400_000_000L, result.valueAt(4));
    assertEquals(SqlTypeDescriptor.timestampWithTimeZone(6), result.typeDescriptorAt(0));
    assertEquals(SqlTypeDescriptor.timestamp(6), result.typeDescriptorAt(1));
    assertEquals(SqlTypeDescriptor.DATE, result.typeDescriptorAt(5));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "SELECT CAST(observed AS TIMESTAMP(3)) FROM moments WHERE id=1",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT CAST(captured AS VARCHAR(32)) FROM moments WHERE id=1",
            result));
    assertEquals("2024-02-29 10:00:00.123456+00:00", text(result, 0));
    assertEquals(
        StatusCode.STRING_DATA_RIGHT_TRUNCATION,
        session.execute(
            "SELECT CAST(observed AS VARCHAR(5)) FROM moments WHERE id=1",
            result));

    assertEquals(
        StatusCode.OK,
        session.execute(
            "INSERT INTO moments VALUES "
                + "(3,DATE '2024-03-31',TIMESTAMP '2024-03-31 01:30:00',NULL,NULL),"
                + "(4,DATE '2024-10-27',TIMESTAMP '2024-10-27 01:30:00',NULL,NULL),"
                + "(5,DATE '1840-01-01',TIMESTAMP '1840-01-01 12:00:00',NULL,NULL),"
                + "(6,DATE '0001-01-01',TIMESTAMP '0001-01-01 00:00:00',NULL,NULL),"
                + "(7,DATE '2024-07-01',TIMESTAMP '2024-07-01 12:00:00',NULL,NULL)",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT observed AT TIME ZONE 'Europe/London' FROM moments WHERE id=3",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT observed AT TIME ZONE 'Europe/London' FROM moments WHERE id=4",
            result));
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.execute(
            "SELECT observed AT TIME ZONE 'Europe/London' FROM moments WHERE id=5",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT observed AT TIME ZONE '+00:00', "
                + "observed AT TIME ZONE '+01:00' FROM moments WHERE id=4",
            result));
    assertEquals(3_600_000_000L, result.valueAt(0) - result.valueAt(1));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT observed AT TIME ZONE 'UTC', "
                + "observed AT TIME ZONE 'Europe/London' FROM moments WHERE id=7",
            result));
    assertEquals(3_600_000_000L, result.valueAt(0) - result.valueAt(1));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "SELECT observed AT TIME ZONE '+14:00' FROM moments WHERE id=6",
            result));
    QueryOpenResult failed = new QueryOpenResult();
    assertEquals(
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
        session.beginQuery(
            "SELECT observed AT TIME ZONE 'Europe/London' "
                + "FROM moments ORDER BY id DESC",
            failed));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT id FROM moments WHERE id=1", result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT CAST(source_text AS DATE) FROM moments WHERE id=1", result));
    assertEquals(19_782, result.valueAt(0));
    assertEquals(
        StatusCode.INVALID_DATETIME_FORMAT,
        session.execute(
            "SELECT CAST('2024/02/29' AS DATE) FROM moments WHERE id=1", result));
    assertEquals(
        StatusCode.DATETIME_FIELD_OVERFLOW,
        session.execute(
            "SELECT CAST('0000-01-01' AS DATE) FROM moments WHERE id=1", result));
  }

  private static void assertStableCurrentProjection(
      RiverSession session, CommandResult result) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE '+02:00'", result));
    assertActiveScanZoneLifetime(session, result, opened);
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT CURRENT_TIMESTAMP AS captured, LOCALTIMESTAMP AS wall "
                + "FROM moments ORDER BY day",
            opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    long captured = row.valueAt(0);
    assertEquals(7_200_000_000L, row.valueAt(1) - captured);
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(captured, row.valueAt(0));
    assertEquals(7_200_000_000L, row.valueAt(1) - captured);
    assertEnd(query, row, result);
    assertEquals(
        StatusCode.OK,
        session.execute(
            "SELECT observed, CAST(observed AS TIMESTAMP(6) WITH TIME ZONE), "
                + "captured, CAST(captured AS TIMESTAMP(6)) "
                + "FROM moments WHERE id=1",
            result));
    assertEquals(7_200_000_000L, result.valueAt(0) - result.valueAt(1));
    assertEquals(7_200_000_000L, result.valueAt(3) - result.valueAt(2));
    assertEquals(StatusCode.OK, session.execute("SET TIME ZONE 'UTC'", result));
  }

  private static void assertActiveScanZoneLifetime(
      RiverSession session, CommandResult result, QueryOpenResult opened) {
    assertEquals(
        StatusCode.OK,
        session.beginQuery(
            "SELECT observed, CAST(observed AS TIMESTAMP(6) WITH TIME ZONE) "
                + "FROM moments WHERE id=1",
            opened));
    assertEquals(
        StatusCode.CONFLICT,
        session.execute("SET TIME ZONE 'UTC'", result));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    assertEquals(StatusCode.OK, query.next(row));
    assertEquals(7_200_000_000L, row.valueAt(0) - row.valueAt(1));
    assertEnd(query, row, result);
  }

  private static void assertProjection(RowResult row) {
    assertEquals(8, row.columnCount());
    assertEquals(1, row.valueAt(0));
    assertEquals(2024, row.valueAt(1));
    assertEquals(19_783, row.valueAt(2));
    assertEquals(0, row.valueAt(3));
    assertEquals(SqlTypeDescriptor.varchar(26), row.typeDescriptorAt(4));
    char[] text = new char[32];
    int length = row.copyTextAt(4, text, 0);
    assertEquals("2024-02-29 10:00:00.123456", new String(text, 0, length));
    assertEquals(true, row.isNull(5));
    assertEquals(SqlTypeDescriptor.timestamp(3), row.typeDescriptorAt(6));
    assertEquals(true, row.isNull(6));
    assertEquals(SqlTypeDescriptor.DATE, row.typeDescriptorAt(7));
    assertEquals(true, row.isNull(7));
  }

  private static String text(CommandResult result, int column) {
    char[] text = new char[32];
    int length = result.copyTextAt(column, text, 0);
    return new String(text, 0, length);
  }

  private static void insertSpillRows(RiverSession session, CommandResult result) {
    for (int first = 0; first < 1_025; first += SqlCommand.MAXIMUM_INSERT_ROWS) {
      int last = Math.min(1_025, first + SqlCommand.MAXIMUM_INSERT_ROWS);
      StringBuilder sql = new StringBuilder("INSERT INTO projection_spill VALUES ");
      for (int index = first; index < last; index++) {
        if (index > first) sql.append(',');
        int timestampOffset = index == 1_024 ? 1 : index * 2;
        sql.append('(').append(index + 1).append(",TIMESTAMP '")
            .append(timestamp(timestampOffset)).append("')");
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

  private static void assertEnd(
      RiverQuery query, RowResult row, CommandResult result) {
    assertEquals(StatusCode.OK, query.next(row));
    assertFalse(row.isAvailable());
    assertEquals(StatusCode.OK, query.close(result));
  }

  private static void assertRowCount(
      RiverSession session, CommandResult result, String sql, int expected) {
    QueryOpenResult opened = new QueryOpenResult();
    assertEquals(StatusCode.OK, session.beginQuery(sql, opened));
    RiverQuery query = opened.query();
    RowResult row = new RowResult();
    int count = 0;
    while (true) {
      assertEquals(StatusCode.OK, query.next(row));
      if (!row.isAvailable()) break;
      count++;
    }
    assertEquals(expected, count);
    assertEquals(StatusCode.OK, query.close(result));
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
}
