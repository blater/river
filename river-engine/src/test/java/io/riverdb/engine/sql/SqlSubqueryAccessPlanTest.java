package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlSubqueryAccessPlanTest {
  @Test
  void selectsNormalizedRawAncestorEqualityAndSafeRanges(@TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("CREATE INDEX inner_region ON inner_rows(region)");

    String equality = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE i.region=o.region)";
    String reversedEquality = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE o.region=i.region)";
    String less = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE i.region<o.region)";
    String reversedLess = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE o.region>i.region)";
    String primary = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE i.id=o.value)";
    String orderedPrimary = "SELECT o.id,o.id+0 AS sorted FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE i.id=o.value) ORDER BY sorted";
    assertAccess(fixture.session(), equality, "index", 2);
    assertAccess(fixture.session(), reversedEquality, "index", 2);
    assertAccess(fixture.session(), less, "index", 2);
    assertAccess(fixture.session(), reversedLess, "index", 2);
    assertAccess(fixture.session(), primary, "primary", 0);
    assertAccess(fixture.session(), orderedPrimary, "primary", 0);
    fixture.assertRows(equality, 1, 2, 3);
    fixture.assertRows(reversedEquality, 1, 2, 3);
    fixture.assertRows(less, 3, 4);
    fixture.assertRows(reversedLess, 3, 4);
    fixture.assertRows(primary, 1);
    fixture.assertRows(orderedPrimary, 1);

    seedTemporalBounds(fixture);
    String timeZeroEquality = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE c.value0=p.bound0)";
    String timeThreeEquality = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE c.value3=p.bound3)";
    String timeZeroRange = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE c.value0>=p.bound0)";
    String reversedTimeRange = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE p.bound3<=c.value3)";
    assertAccess(fixture.session(), timeZeroEquality, "index", 1);
    assertAccess(fixture.session(), timeThreeEquality, "index", 2);
    assertAccess(fixture.session(), timeZeroRange, "index", 1);
    assertAccess(fixture.session(), reversedTimeRange, "index", 2);
    fixture.assertRows(timeZeroEquality, 1, 2);
    fixture.assertRows(timeThreeEquality, 1, 2);
    fixture.assertRows(timeZeroRange, 1, 2);
    fixture.assertRows(reversedTimeRange, 1, 2);

    seedBigintBounds(fixture);
    String bigintLess = "SELECT p.id FROM bound_parent p WHERE EXISTS "
        + "(SELECT c.id FROM bound_child c WHERE c.value<p.boundary)";
    String tableLess = "SELECT p.id FROM bound_parent p WHERE EXISTS "
        + "(SELECT c.id FROM bound_child c WHERE c.value+0<p.boundary)";
    assertAccess(fixture.session(), bigintLess, "index", 1);
    assertAccess(fixture.session(), tableLess, "table", -1);
    fixture.assertRows(bigintLess, 2);
    fixture.assertRows(tableLess, 2);
    fixture.close();
  }

  @Test
  void usesDescriptorRangesAndReportsTableForNonMandatoryShapes(
      @TempDir Path root) {
    SqlSubqueryAcceptanceFixture fixture = SqlSubqueryAcceptanceFixture.create(root);
    fixture.execute("CREATE INDEX inner_region ON inner_rows(region)");
    seedTemporalBounds(fixture);
    seedBigintBounds(fixture);

    String timeGreater = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE c.value0>p.bound0)";
    String reversedTimeGreater = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE p.bound0<c.value0)";
    String timeLessOrEqual = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE c.value3<=p.bound3)";
    String reversedTimeLessOrEqual = "SELECT p.id FROM time_parent p WHERE EXISTS "
        + "(SELECT c.id FROM time_child c WHERE p.bound3>=c.value3)";
    String orderedTimeGreater = "SELECT p.id,p.id+0 AS sorted FROM time_parent p "
        + "WHERE EXISTS (SELECT c.id FROM time_child c WHERE c.value0>p.bound0) "
        + "ORDER BY sorted";
    String orderedTimeLessOrEqual = "SELECT p.id,p.id+0 AS sorted FROM time_parent p "
        + "WHERE EXISTS (SELECT c.id FROM time_child c WHERE c.value3<=p.bound3) "
        + "ORDER BY sorted";
    assertAccess(fixture.session(), timeGreater, "index", 1);
    assertAccess(fixture.session(), reversedTimeGreater, "index", 1);
    assertAccess(
        fixture.session(),
        "SELECT p.id FROM time_parent p WHERE EXISTS "
            + "(SELECT c.id FROM time_child c WHERE c.value3>p.bound3)",
        "index", 2);
    assertAccess(
        fixture.session(),
        timeLessOrEqual,
        "index", 2);
    assertAccess(fixture.session(), reversedTimeLessOrEqual, "index", 2);
    assertAccess(fixture.session(), orderedTimeGreater, "index", 1);
    assertAccess(fixture.session(), orderedTimeLessOrEqual, "index", 2);
    fixture.assertRows(timeGreater, 1);
    fixture.assertRows(reversedTimeGreater, 1);
    fixture.assertRows(timeLessOrEqual, 1, 2);
    fixture.assertRows(reversedTimeLessOrEqual, 1, 2);
    fixture.assertRows(orderedTimeGreater, 1);
    fixture.assertRows(orderedTimeLessOrEqual, 1, 2);
    String bigintGreaterOrEqual = "SELECT p.id FROM bound_parent p WHERE EXISTS "
        + "(SELECT c.id FROM bound_child c WHERE c.value>=p.boundary)";
    assertAccess(fixture.session(), bigintGreaterOrEqual, "index", 1);
    fixture.assertRows(bigintGreaterOrEqual, 1, 2);
    seedMixedTemporalPrecision(fixture);
    String mixedEquality = "SELECT p.id FROM precision_parent p WHERE EXISTS "
        + "(SELECT c.id FROM precision_child c WHERE c.value0=p.bound3)";
    String mixedGreater = "SELECT p.id,p.id+0 AS sorted FROM precision_parent p "
        + "WHERE EXISTS (SELECT c.id FROM precision_child c "
        + "WHERE c.value0>p.bound3) ORDER BY sorted";
    String mixedLessOrEqual = "SELECT p.id,p.id+0 AS sorted FROM precision_parent p "
        + "WHERE EXISTS (SELECT c.id FROM precision_child c "
        + "WHERE c.value0<=p.bound3) ORDER BY sorted";
    assertAccess(fixture.session(), mixedEquality, "index", 1);
    assertAccess(fixture.session(), mixedGreater, "index", 1);
    assertAccess(fixture.session(), mixedLessOrEqual, "index", 1);
    fixture.assertRows(mixedEquality, 2);
    fixture.assertRows(mixedGreater, 1);
    fixture.assertRows(mixedLessOrEqual, 2);
    assertAccess(
        fixture.session(),
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i WHERE i.region+0=o.region)",
        "table", -1);
    assertAccess(
        fixture.session(),
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i WHERE i.region=o.region+0)",
        "table", -1);
    assertAccess(
        fixture.session(),
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i WHERE i.region=i.value)",
        "table", -1);
    assertAccess(
        fixture.session(),
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i "
            + "WHERE i.region=o.region OR i.value=o.value)",
        "table", -1);
    String negated = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i WHERE NOT(i.region=o.region))";
    assertAccess(fixture.session(), negated, "table", -1);
    fixture.assertRows(negated, 1, 2, 3, 4);
    assertAccess(
        fixture.session(),
        "SELECT o.id FROM outer_rows o WHERE EXISTS "
            + "(SELECT i.id FROM inner_rows i JOIN scalar_rows s ON i.id=s.id "
            + "WHERE i.region=o.region)",
        "table", -1);

    String indexedResidual = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i "
        + "WHERE i.region=o.region AND i.value=o.value)";
    String tableResidual = "SELECT o.id FROM outer_rows o WHERE EXISTS "
        + "(SELECT i.id FROM inner_rows i "
        + "WHERE i.region+0=o.region AND i.value=o.value)";
    assertAccess(fixture.session(), indexedResidual, "index", 2);
    assertAccess(fixture.session(), tableResidual, "table", -1);
    fixture.assertRows(indexedResidual, 1);
    fixture.assertRows(tableResidual, 1);
    seedResidualFailure(fixture);
    String failingResidual = "SELECT p.id FROM failure_parent p WHERE EXISTS "
        + "(SELECT c.id FROM failure_child c WHERE c.region=p.region AND "
        + "p.expected=c.observed AT TIME ZONE 'Europe/London')";
    String failingTableResidual = "SELECT p.id FROM failure_parent p WHERE EXISTS "
        + "(SELECT c.id FROM failure_child c WHERE c.region+0=p.region AND "
        + "p.expected=c.observed AT TIME ZONE 'Europe/London')";
    assertAccess(fixture.session(), failingResidual, "index", 1);
    assertAccess(fixture.session(), failingTableResidual, "table", -1);
    assertRuntimeFailure(fixture, failingResidual);
    assertRuntimeFailure(fixture, failingTableResidual);
    fixture.close();
  }

  private static void seedResidualFailure(SqlSubqueryAcceptanceFixture fixture) {
    fixture.execute(
        "CREATE TABLE failure_parent (id BIGINT PRIMARY KEY,region BIGINT,"
            + "expected TIMESTAMP(6) WITH TIME ZONE)");
    fixture.execute(
        "CREATE TABLE failure_child (id BIGINT PRIMARY KEY,region BIGINT,"
            + "observed TIMESTAMP(6))");
    fixture.execute("CREATE INDEX failure_child_region ON failure_child(region)");
    fixture.execute(
        "INSERT INTO failure_parent VALUES "
            + "(1,7,TIMESTAMP WITH TIME ZONE '2024-03-31 01:30:00+00:00')");
    fixture.execute(
        "INSERT INTO failure_child VALUES "
            + "(1,7,TIMESTAMP '2024-03-31 01:30:00')");
  }

  private static void assertRuntimeFailure(
      SqlSubqueryAcceptanceFixture fixture, String sql) {
    for (int attempt = 0; attempt < 2; attempt++) {
      SqlScanCursor cursor = new SqlScanCursor();
      assertEquals(
          StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
          fixture.session().beginScan(sql, cursor),
          sql);
      assertFalse(cursor.isActive(), sql);
      assertEquals(StatusCode.OK, cursor.reset(), sql);
    }
    fixture.assertRows("SELECT id FROM outer_rows", 1, 2, 3, 4);
  }

  private static void seedTemporalBounds(SqlSubqueryAcceptanceFixture fixture) {
    fixture.execute(
        "CREATE TABLE time_parent "
            + "(id BIGINT PRIMARY KEY,bound0 TIME(0),bound3 TIME(3))");
    fixture.execute(
        "CREATE TABLE time_child "
            + "(id BIGINT PRIMARY KEY,value0 TIME(0),value3 TIME(3))");
    fixture.execute("CREATE INDEX time_child_value0 ON time_child(value0)");
    fixture.execute("CREATE INDEX time_child_value3 ON time_child(value3)");
    fixture.execute(
        "INSERT INTO time_parent VALUES "
            + "(1,TIME '00:00:00',TIME '00:00:00.000'),"
            + "(2,TIME '23:59:59',TIME '23:59:59.999')");
    fixture.execute(
        "INSERT INTO time_child VALUES "
            + "(1,TIME '00:00:00',TIME '00:00:00.000'),"
            + "(2,TIME '23:59:59',TIME '23:59:59.999')");
  }

  private static void seedBigintBounds(SqlSubqueryAcceptanceFixture fixture) {
    fixture.execute(
        "CREATE TABLE bound_parent (id BIGINT PRIMARY KEY,boundary BIGINT)");
    fixture.execute(
        "CREATE TABLE bound_child (id BIGINT PRIMARY KEY,value BIGINT)");
    fixture.execute("CREATE INDEX bound_child_value ON bound_child(value)");
    fixture.execute(
        "INSERT INTO bound_parent VALUES "
            + "(1,-9223372036854775808),(2,9223372036854775807)");
    fixture.execute(
        "INSERT INTO bound_child VALUES "
            + "(1,-9223372036854775808),(2,0),(3,9223372036854775807)");
  }

  private static void seedMixedTemporalPrecision(
      SqlSubqueryAcceptanceFixture fixture) {
    fixture.execute(
        "CREATE TABLE precision_parent "
            + "(id BIGINT PRIMARY KEY,bound3 TIME(3))");
    fixture.execute(
        "CREATE TABLE precision_child "
            + "(id BIGINT PRIMARY KEY,value0 TIME(0))");
    fixture.execute("CREATE INDEX precision_child_value0 ON precision_child(value0)");
    fixture.execute(
        "INSERT INTO precision_parent VALUES "
            + "(1,TIME '00:00:00.500'),(2,TIME '00:00:01.000')");
    fixture.execute(
        "INSERT INTO precision_child VALUES (1,TIME '00:00:01')");
  }

  private static void assertAccess(
      SqlSession session, String sql, String operator, long column) {
    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    SqlExecutionResult result = new SqlExecutionResult();
    assertEquals(StatusCode.OK, session.beginScan("EXPLAIN " + sql, cursor), sql);
    long actualOperator = 0;
    long detail = Long.MIN_VALUE;
    StatusCode status;
    while ((status = session.nextScan(cursor, row)) == StatusCode.OK) {
      if (row.valueAt(0) == PackedText.pack("primary")
          || row.valueAt(0) == PackedText.pack("index")
          || row.valueAt(0) == PackedText.pack("table")) {
        actualOperator = row.valueAt(0);
        detail = row.valueAt(1);
      }
    }
    assertEquals(StatusCode.CONFLICT, status, sql);
    assertEquals(PackedText.pack(operator), actualOperator, sql);
    assertEquals(column, detail, sql);
    assertEquals(StatusCode.OK, session.closeScan(cursor, result), sql);
  }
}
