package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract oracle for admitted sibling, recursive, lazy, and three-valued truth. */
final class SqlSubqueryTruthAcceptanceTest {

  @Test
  void evaluatesSiblingAndRecursiveLeavesWithoutLosingContinuation(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE "
            + "EXISTS (SELECT i.id FROM inner_rows i WHERE i.region=o.region) "
            + "AND o.value IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=o.region) "
            + "AND o.value=(SELECT i.value FROM inner_rows i WHERE i.id=11)",
        1);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT m.id FROM inner_rows m WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i "
            + "WHERE i.id=m.id AND i.region=o.region) "
            + "AND m.value=o.value) AND o.id>0",
        1);

    fixture.close();
  }

  @Test
  void preservesCompleteMembershipThreeValuedLogic(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=99)");
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value NOT IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=99)",
        1, 2, 3, 4);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=1)",
        1);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value NOT IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=1)");

    fixture.close();
  }

  @Test
  void skipsUnreachedScalarCardinalityFailures(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.id=o.id OR "
            + "o.value=(SELECT i.value FROM inner_rows i)",
        1, 2, 3, 4);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.id<>o.id AND "
            + "o.value=(SELECT i.value FROM inner_rows i)");
    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(
        StatusCode.CARDINALITY_VIOLATION,
        fixture.session().beginScan(
            "SELECT o.id FROM outer_rows o WHERE o.value="
                + "(SELECT i.value FROM inner_rows i)",
            cursor));
    assertFalse(cursor.isActive());
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);

    fixture.close();
  }

  @Test
  void appliesScalarRowsLimitAndRemainingMembershipTruth(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value="
            + "(SELECT i.value FROM inner_rows i WHERE i.id=99)");
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value="
            + "(SELECT i.value FROM inner_rows i WHERE i.id=11)",
        1);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE "
            + "(SELECT i.value FROM inner_rows i WHERE i.id=11)<o.value",
        2, 4);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value="
            + "(SELECT i.value FROM inner_rows i WHERE i.id=10)");
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value="
            + "(SELECT i.value FROM inner_rows i WHERE i.id>=11 LIMIT 1)",
        1);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value="
            + "(SELECT i.value FROM inner_rows i LIMIT 0)");
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=2)",
        1);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value NOT IN "
            + "(SELECT i.value FROM inner_rows i WHERE i.region=2)",
        2, 4);

    fixture.close();
  }

  @Test
  void evaluatesScalarCardinalityAndNullAcrossValueFamilies(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute(
        "CREATE TABLE scalar_types (id BIGINT PRIMARY KEY,"
            + "amount DECIMAL(8,2),enabled BOOLEAN,day DATE,alarm TIME(6),"
            + "observed TIMESTAMP(6),zoned TIMESTAMP(6) WITH TIME ZONE,"
            + "label VARCHAR(32))");
    fixture.execute(
        "INSERT INTO scalar_types VALUES "
            + "(1,12.30,TRUE,DATE '2024-02-29',TIME '01:02:03.400000',"
            + "TIMESTAMP '2024-02-29 12:00:00.123400',"
            + "TIMESTAMP WITH TIME ZONE '2024-02-29 12:00:00.123400+01:30',"
            + "'多🙂'),"
            + "(2,12.30,TRUE,DATE '2024-02-29',TIME '01:02:03.400000',"
            + "TIMESTAMP '2024-02-29 12:00:00.123400',"
            + "TIMESTAMP WITH TIME ZONE '2024-02-29 12:00:00.123400+01:30',"
            + "'多🙂'),"
            + "(3,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");

    assertScalarFamily(fixture, "12.300", "amount");
    assertScalarFamily(fixture, "TRUE", "enabled");
    assertScalarFamily(fixture, "DATE '2024-02-29'", "day");
    assertScalarFamily(fixture, "TIME '01:02:03.4'", "alarm");
    assertScalarFamily(
        fixture, "TIMESTAMP '2024-02-29 12:00:00.1234'", "observed");
    assertScalarFamily(
        fixture,
        "TIMESTAMP WITH TIME ZONE '2024-02-29 10:30:00.1234+00:00'",
        "zoned");
    assertScalarFamily(fixture, "'多🙂'", "label");
    fixture.close();
  }

  @Test
  void preservesDescendantReplayWhenParentMembershipFillsTheArena(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("CREATE TABLE cache_a (id BIGINT PRIMARY KEY,value BIGINT)");
    fixture.execute("CREATE TABLE cache_b (id BIGINT PRIMARY KEY,value BIGINT)");
    insertRange(fixture, "cache_a", 1, 602, 1);
    insertRange(fixture, "cache_b", 1, 600, 1_001);
    fixture.execute("INSERT INTO cache_b VALUES (601,10),(602,20)");

    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE o.value IN "
            + "(SELECT b.value FROM cache_b b WHERE b.id IN "
            + "(SELECT a.value FROM cache_a a))",
        1, 2);
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);
    fixture.close();
  }

  @Test
  void discardsExistsProjectionWithoutTemporalOrRowEvaluation(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture =
        SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute(
        "CREATE TABLE temporal_rows (id BIGINT PRIMARY KEY,"
            + "observed TIMESTAMP(6),zoned TIMESTAMP WITH TIME ZONE)");
    fixture.execute(
        "INSERT INTO temporal_rows VALUES "
            + "(1,TIMESTAMP '2024-01-01 12:00:00',"
            + "TIMESTAMP WITH TIME ZONE '2024-01-01 12:00:00+00:00'),"
            + "(2,TIMESTAMP '2024-03-31 01:30:00',"
            + "TIMESTAMP WITH TIME ZONE '2024-03-31 01:30:00+00:00')");

    fixture.assertRows(
        "SELECT t.id FROM temporal_rows t WHERE EXISTS "
            + "(SELECT i.observed AT TIME ZONE 'No/Such' "
            + "FROM temporal_rows i WHERE i.id=t.id)",
        1, 2);
    fixture.assertRows(
        "SELECT t.id FROM temporal_rows t WHERE EXISTS "
            + "(SELECT 1/0 FROM temporal_rows i WHERE i.id=t.id)",
        1, 2);
    fixture.assertRows(
        "SELECT t.id FROM temporal_rows t WHERE EXISTS "
            + "(SELECT i.observed AT TIME ZONE 'No/Such' "
            + "FROM temporal_rows i WHERE i.id=99)");
    assertBegin(
        fixture,
        "SELECT t.id FROM temporal_rows t WHERE t.zoned="
            + "(SELECT i.observed AT TIME ZONE 'No/Such' FROM temporal_rows i)",
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    assertBegin(
        fixture,
        "SELECT t.id FROM temporal_rows t WHERE t.zoned IN "
            + "(SELECT i.observed AT TIME ZONE 'No/Such' FROM temporal_rows i)",
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    assertBegin(
        fixture,
        "SELECT t.id FROM temporal_rows t WHERE EXISTS "
            + "(SELECT i.missing FROM temporal_rows i)",
        StatusCode.INVALID_EXTERNAL_INPUT);
    assertRepeatedTerminal(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE "
            + "TIMESTAMP WITH TIME ZONE '2024-03-31 01:30:00+00:00'="
            + "(SELECT i.observed AT TIME ZONE 'Europe/London' "
            + "FROM temporal_rows i WHERE i.id=2)",
        StatusCode.INVALID_TIME_ZONE_DISPLACEMENT);
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);

    fixture.close();
  }

  private static void assertBegin(
      SqlSubqueryAcceptanceFixture fixture, String sql, StatusCode expected) {
    SqlScanCursor cursor = new SqlScanCursor();
    assertEquals(expected, fixture.session().beginScan(sql, cursor), sql);
    if (expected.isOk()) {
      assertEquals(StatusCode.OK, fixture.session().closeScan(cursor, fixture.result()));
    } else {
      assertEquals(StatusCode.OK, cursor.reset());
    }
  }

  private static void assertScalarFamily(
      SqlSubqueryAcceptanceFixture fixture, String literal, String column) {
    String child = "(SELECT s." + column + " FROM scalar_types s WHERE s.id=";
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE " + literal + "=" + child + "1)",
        1, 2, 3, 4);
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE " + literal + "=" + child + "99)");
    fixture.assertRows(
        "SELECT o.id FROM outer_rows o WHERE " + literal + "=" + child + "3)");
    assertRepeatedTerminal(
        fixture,
        "SELECT o.id FROM outer_rows o WHERE " + literal
            + "=(SELECT s." + column + " FROM scalar_types s WHERE s.id<=2)",
        StatusCode.CARDINALITY_VIOLATION);
  }

  private static void assertRepeatedTerminal(
      SqlSubqueryAcceptanceFixture fixture, String sql, StatusCode expected) {
    for (int attempt = 0; attempt < 2; attempt++) {
      SqlScanCursor cursor = new SqlScanCursor();
      assertEquals(expected, fixture.session().beginScan(sql, cursor), sql);
      assertFalse(cursor.isActive(), sql);
      assertEquals(StatusCode.OK, cursor.reset(), sql);
    }
  }

  private static void insertRange(
      SqlSubqueryAcceptanceFixture fixture,
      String table,
      int firstId,
      int count,
      int firstValue) {
    for (int offset = 0; offset < count; offset += 32) {
      int length = Math.min(32, count - offset);
      StringBuilder sql = new StringBuilder("INSERT INTO ")
          .append(table).append(" VALUES ");
      for (int row = 0; row < length; row++) {
        if (row > 0) sql.append(',');
        sql.append('(').append(firstId + offset + row).append(',')
            .append(firstValue + offset + row).append(')');
      }
      fixture.execute(sql.toString());
    }
  }
}
