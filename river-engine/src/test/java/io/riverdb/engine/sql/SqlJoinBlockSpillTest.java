package io.riverdb.engine.sql;

import static io.riverdb.engine.sql.SqlJoinBlockPipelineFixture.assertRowCount;
import static io.riverdb.engine.sql.SqlJoinBlockPipelineFixture.assertRows;
import static io.riverdb.engine.sql.SqlJoinBlockPipelineFixture.assertTextRows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real-path evidence for spilled Unicode JOIN rows consumed by parent blocks. */
final class SqlJoinBlockSpillTest {
  @Test
  void spillsOwnedUnicodeJoinRowsIntoGroupedAndDistinctParents(@TempDir Path root) {
    SqlJoinBlockPipelineFixture fixture = SqlJoinBlockPipelineFixture.open(root);
    SqlSession session = fixture.session;
    SqlExecutionResult result = fixture.result;
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_left "
                + "(id BIGINT PRIMARY KEY,bucket BIGINT,label VARCHAR(20))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_right "
                + "(id BIGINT PRIMARY KEY,label VARCHAR(20))",
            result));
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE TABLE spill_third (id BIGINT PRIMARY KEY,marker BIGINT)",
            result));
    for (int start = 1; start <= 1_025; start += 64) {
      int end = Math.min(1_025, start + 63);
      StringBuilder left = new StringBuilder("INSERT INTO spill_left VALUES ");
      StringBuilder right = new StringBuilder("INSERT INTO spill_right VALUES ");
      StringBuilder third = new StringBuilder("INSERT INTO spill_third VALUES ");
      for (int id = start; id <= end; id++) {
        if (id > start) {
          left.append(',');
          right.append(',');
          third.append(',');
        }
        String label =
            id % 3 == 0 ? "猫" : id % 3 == 1 ? SqlJoinBlockPipelineFixture.HIGH_BMP : "😀";
        left.append('(').append(id).append(',').append(id % 2).append(",'left')");
        third.append('(').append(id).append(',').append(id + 1).append(')');
        right.append('(').append(id).append(",'").append(label).append("')");
      }
      assertEquals(StatusCode.OK, session.execute(left.toString(), result));
      assertEquals(StatusCode.OK, session.execute(right.toString(), result));
      assertEquals(StatusCode.OK, session.execute(third.toString(), result));
    }
    assertDirectThreeRoleSpill(session, result);
    assertRows(
        session,
        result,
        "SELECT bucket,lid FROM (SELECT l.bucket AS bucket,l.id AS lid "
            + "FROM spill_left l JOIN spill_right r ON l.id=r.id "
            + "JOIN spill_third t ON r.id=t.id "
            + "ORDER BY bucket ASC,lid DESC LIMIT 1) joined",
        new long[][] {{0, 1_024}});
    assertEquals(
        StatusCode.OK,
        session.execute(
            "CREATE VIEW spill_join AS SELECT l.id AS lid,l.bucket AS bucket,"
                + "r.label AS label FROM spill_left l JOIN spill_right r "
                + "ON l.id=r.id JOIN spill_third t ON r.id=t.id",
            result));
    assertRows(
        session,
        result,
        "SELECT bucket,COUNT(*) AS n FROM spill_join "
            + "GROUP BY bucket HAVING COUNT(*)>500 ORDER BY bucket",
        new long[][] {{0, 512}, {1, 513}});
    assertTextRows(
        session,
        result,
        "SELECT DISTINCT label FROM spill_join ORDER BY label",
        new String[] {"猫", SqlJoinBlockPipelineFixture.HIGH_BMP, "😀"});
    assertRowCount(session, result, "SELECT lid FROM spill_join", 1_025);
    assertEquals(StatusCode.OK, session.execute("DROP VIEW spill_join", result));
    assertEquals(StatusCode.OK, session.close());
    assertEquals(StatusCode.OK, fixture.database.close());
  }

  private static void assertDirectThreeRoleSpill(
      SqlSession session, SqlExecutionResult result) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    char[] text = new char[4];
    assertEquals(
        StatusCode.OK,
        session.beginScan(
            "SELECT l.id AS lid,r.label AS label FROM spill_left l "
                + "JOIN spill_right r ON l.id=r.id "
                + "JOIN spill_third t ON r.id=t.id ORDER BY label",
            cursor));
    assertEquals(
        SqlTypeDescriptor.TYPE_ID_VARCHAR,
        SqlTypeDescriptor.typeId(session.scanColumnTypeDescriptor(cursor, 1)));
    for (int index = 0; index < 1_025; index++) {
      assertEquals(StatusCode.OK, session.nextScan(cursor, row));
      String expected =
          index < 341
              ? "猫"
              : index < 683 ? SqlJoinBlockPipelineFixture.HIGH_BMP : "😀";
      assertFalse(row.isNull(1), "NULL text at row " + index + " id " + row.valueAt(0));
      int length = row.copyTextAt(1, text, 0);
      assertTrue(length >= 0, "missing text at sorted row " + index);
      assertEquals(expected, new String(text, 0, length));
    }
    assertEquals(StatusCode.CONFLICT, session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, session.closeScan(cursor, result));
    assertEquals(
        StatusCode.OK,
        session.execute("SELECT bucket FROM spill_left WHERE id=1", result));
    assertEquals(1, result.valueAt(0));
  }
}
