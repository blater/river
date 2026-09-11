package io.riverdb.engine.sql;

import static io.riverdb.engine.sql.SqlJoinBlockPipelineFixture.assertRows;
import static io.riverdb.engine.sql.SqlJoinBlockPipelineFixture.assertSingleNull;
import static io.riverdb.engine.sql.SqlJoinBlockPipelineFixture.assertTextRows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path evidence for a deepest JOIN materialized into parent block stages. */
final class SqlJoinBlockPipelineTest {
  @Test
  void feedsProjectionDistinctGroupHavingOrderAndTruthfulPlan(@TempDir Path root) {
    SqlJoinBlockPipelineFixture fixture = SqlJoinBlockPipelineFixture.open(root);
    fixture.createBaseTables();
    SqlSession session = fixture.session;
    SqlExecutionResult result = fixture.result;

    assertRows(
        session,
        result,
        "SELECT lid,rid FROM (SELECT l.id AS lid,r.id AS rid "
            + "FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined ORDER BY lid",
        new long[][] {{1, 11}, {2, 21}, {3, 31}});
    assertTextRows(
        session,
        result,
        "SELECT DISTINCT label FROM (SELECT r.label AS label "
            + "FROM left_rows l JOIN right_rows r ON l.id=r.left_id "
            + "WHERE r.flag=TRUE) joined ORDER BY label",
        new String[] {"fox", "猫", "😀"});
    assertRows(
        session,
        result,
        "SELECT bucket,SUM(amount+1) AS n FROM (SELECT l.bucket AS bucket,"
            + "r.amount AS amount "
            + "FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined "
            + "GROUP BY bucket HAVING SUM(amount+1)>301 ORDER BY bucket",
        new long[][] {{10, 302}});
    assertScalarCounts(session, result);
    assertTextRows(
        session,
        result,
        "SELECT DISTINCT rendered FROM (SELECT CAST(l.observed AS VARCHAR(32)) "
            + "AS rendered FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined ORDER BY rendered",
        new String[] {
          "2024-03-31 00:30:00.000000",
          "2024-03-31 01:30:00.000000",
          "2024-04-01 00:30:00.000000"
        });
    assertLeftComputedNullability(session, result);
    SqlJoinBlockPlanAssertions.assertJoinPlans(session, result);
    SqlJoinBlockPlanAssertions.assertTemporalAtomicFailure(session, result);
    assertRows(
        session,
        result,
        "SELECT lid,COUNT(*) AS n FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid DESC LIMIT 1) "
            + "joined GROUP BY lid",
        new long[][] {{3, 1}});
    assertRows(
        session,
        result,
        "SELECT lid FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid DESC LIMIT 1) "
            + "joined",
        new long[][] {{3}});
    assertRows(
        session,
        result,
        "SELECT bucket,amount FROM (SELECT l.bucket AS bucket,r.amount AS amount "
            + "FROM left_rows l JOIN right_rows r ON l.id=r.left_id "
            + "ORDER BY bucket ASC,amount DESC LIMIT 1) joined",
        new long[][] {{10, 200}});
    assertRows(
        session,
        result,
        "SELECT bucket,amount FROM (SELECT l.bucket AS bucket,r.amount AS amount "
            + "FROM left_rows l JOIN right_rows r ON l.id=r.left_id) joined "
            + "ORDER BY bucket ASC,amount DESC LIMIT 1",
        new long[][] {{10, 200}});
    assertRows(
        session,
        result,
        "SELECT l.id AS lid,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.id "
            + "ORDER BY n DESC,lid DESC LIMIT 2",
        new long[][] {{1, 2}, {3, 1}});
    assertRows(
        session,
        result,
        "SELECT COUNT(*) AS n FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid DESC LIMIT 0) "
            + "joined",
        new long[][] {{0}});
    assertRows(
        session,
        result,
        "SELECT l.bucket AS bucket,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.bucket "
            + "ORDER BY n DESC LIMIT 1",
        new long[][] {{10, 3}});
    assertRows(
        session,
        result,
        "SELECT l.bucket AS bucket,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.bucket "
            + "ORDER BY n DESC",
        new long[][] {{10, 3}, {20, 1}});
    assertRows(
        session,
        result,
        "SELECT l.bucket AS bucket,COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id GROUP BY l.bucket LIMIT 0",
        new long[0][]);
    assertRows(
        session,
        result,
        "SELECT COUNT(*) AS n FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id LIMIT 0",
        new long[0][]);
    assertDeepestOnly(session, result);

    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW direct_join AS SELECT l.id AS lid FROM left_rows l "
                + "JOIN right_rows r ON l.id=r.left_id",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW derived_join AS SELECT lid FROM "
                + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
                + "ON l.id=r.left_id) joined",
            result));
    assertRows(
        session,
        result,
        "SELECT lid FROM direct_join ORDER BY lid",
        new long[][] {{1}, {1}, {2}, {3}});
    assertRows(
        session,
        result,
        "SELECT lid FROM derived_join ORDER BY lid",
        new long[][] {{1}, {1}, {2}, {3}});
    assertEquals(StatusCode.OK, session.execute("DROP VIEW direct_join", result));
    assertEquals(StatusCode.OK, session.execute("DROP VIEW derived_join", result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM left_rows WHERE id=1", result));
    assertEquals(10, result.valueAt(0));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  private static void assertLeftComputedNullability(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT lid,adjusted FROM (SELECT l.id AS lid,r.amount+1 AS adjusted "
                + "FROM left_rows l LEFT JOIN right_rows r "
                + "ON l.id=r.left_id AND r.flag=TRUE) joined ORDER BY lid",
            cursor));
    assertFalse(session.scanColumnIsNullable(cursor, 0));
    assertTrue(session.scanColumnIsNullable(cursor, 1));
    for (long[] expected : new long[][] {{1, 101}, {2, 201}, {3, 301}}) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      assertEquals(expected[0], row.valueAt(0));
      assertEquals(expected[1], row.valueAt(1));
    }
    assertEquals(StatusCode.OK, session.nextScan(cursor, row));
    assertEquals(4, row.valueAt(0));
    assertTrue(row.isNull(1));
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));

    assertRows(
        session,
        result,
        "SELECT lid,rid FROM (SELECT l.id AS lid,r.id AS rid "
            + "FROM left_rows l LEFT JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE "
            + "WHERE r.amount>150 OR r.id IS NULL) joined ORDER BY lid",
        new long[][] {{2, 21}, {3, 31}, {4, 0}});
  }

  private static void assertScalarCounts(
      SqlSession session, SqlExecutionResult result) {
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id AND r.flag=TRUE) joined) counted",
        new long[][] {{3}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id WHERE l.id<0) joined) counted",
        new long[][] {{0}});
    assertRows(
        session,
        result,
        "SELECT SUM(amount+1) AS total FROM (SELECT r.amount AS amount FROM "
            + "left_rows l JOIN right_rows r ON l.id=r.left_id "
            + "WHERE r.flag=TRUE) joined",
        new long[][] {{603}});
    assertRows(
        session,
        result,
        "SELECT COUNT(*) AS n FROM (SELECT r.amount AS amount FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id) joined WHERE amount>150",
        new long[][] {{2}});
    assertRows(
        session,
        result,
        "SELECT COUNT(n) AS n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id) joined) counted",
        new long[][] {{1}});
    assertRows(
        session,
        result,
        "SELECT n FROM (SELECT COUNT(*) AS n FROM "
            + "(SELECT l.id AS lid FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id) joined) counted WHERE n<0",
        new long[0][]);
    assertSingleNull(
        session,
        result,
        "SELECT total FROM (SELECT SUM(amount+1) AS total FROM "
            + "(SELECT r.amount AS amount FROM left_rows l JOIN right_rows r "
            + "ON l.id=r.left_id WHERE l.id<0) joined) totals");
  }

  private static void assertDeepestOnly(
      SqlSession session, SqlExecutionResult result) {
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginScan(
            "SELECT j.lid FROM (SELECT l.id AS lid FROM left_rows l) j "
                + "JOIN right_rows r ON j.lid=r.left_id",
            new SqlScanCursor()));
    assertEquals(
        StatusCode.FEATURE_NOT_SUPPORTED,
        session.beginScan(
            "SELECT j.lid FROM (SELECT l.id AS lid FROM left_rows l "
                + "JOIN right_rows r ON l.id=r.left_id) j "
                + "JOIN right_rows x ON j.lid=x.left_id",
            new SqlScanCursor()));
    assertRows(
        session,
        result,
        "SELECT lid FROM (SELECT l.id AS lid FROM left_rows l "
            + "JOIN right_rows r ON l.id=r.left_id ORDER BY lid) joined",
        new long[][] {{1}, {1}, {2}, {3}});
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM left_rows WHERE id=3", result));
    assertEquals(20, result.valueAt(0));
  }
}
