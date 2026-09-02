package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalDatabase;
import io.riverdb.engine.relational.RelationalDatabaseOpenResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDescriptorScalarAggregateTest {
  private static final DatabaseIncarnation DATABASE =
      DatabaseIncarnation.of(0x4453435343414c41L, 0x5241474730303031L);
  private static final WalGeneration GENERATION = WalGeneration.of(1);

  @Test
  void aggregatesEveryNumericFamilyAndTextWithoutLosingNullSemantics(
      @TempDir Path root) {
    Fixture fixture = open(root);
    SqlExecutionResult result = fixture.result;
    assertEquals(StatusCode.OK, fixture.session.execute(
        "CREATE TABLE metrics (id INTEGER PRIMARY KEY,amount DECIMAL(10,2),"
            + "units INTEGER,real_value REAL,double_value DOUBLE PRECISION,"
            + "label VARCHAR(16))", result));
    assertEquals(StatusCode.OK, fixture.session.execute(
        "INSERT INTO metrics VALUES "
            + "(1,10.25,2,1.5,2.25,'zeta'),"
            + "(2,20.75,4,2.5,3.75,'alpha'),"
            + "(3,NULL,NULL,NULL,NULL,NULL)", result));

    assertFixed(fixture, "SELECT SUM(amount) FROM metrics", 3_100,
        SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 2));
    assertFixed(fixture, "SELECT AVG(amount) FROM metrics", 15_500_000,
        SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 6));
    assertFixed(fixture, "SELECT SUM(units) FROM metrics", 6, SqlTypeDescriptor.BIGINT);
    assertFixed(fixture, "SELECT AVG(units) FROM metrics", 3_000_000,
        SqlTypeDescriptor.decimal(SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 6));
    assertFixed(fixture, "SELECT SUM(real_value) FROM metrics",
        SqlApproximateNumeric.realBits(4.0f), SqlTypeDescriptor.REAL);
    assertFixed(fixture, "SELECT AVG(real_value) FROM metrics",
        SqlApproximateNumeric.doubleBits(2.0), SqlTypeDescriptor.DOUBLE);
    assertFixed(fixture, "SELECT SUM(double_value) FROM metrics",
        SqlApproximateNumeric.doubleBits(6.0), SqlTypeDescriptor.DOUBLE);
    assertText(fixture, "SELECT MIN(label) FROM metrics", "alpha");
    assertText(fixture, "SELECT MAX(label) FROM metrics", "zeta");
    assertFixed(fixture,
        "SELECT COUNT(amount) FROM metrics WHERE id>=2", 1, SqlTypeDescriptor.BIGINT);
    assertFixed(fixture,
        "SELECT COUNT(units) FROM metrics WHERE id=3", 0, SqlTypeDescriptor.BIGINT);
    assertFixed(fixture,
        "SELECT COUNT(DISTINCT amount) FROM metrics", 2, SqlTypeDescriptor.BIGINT);
    assertFixed(fixture,
        "SELECT COUNT(DISTINCT real_value) FROM metrics", 2, SqlTypeDescriptor.BIGINT);
    assertFixed(fixture,
        "SELECT COUNT(DISTINCT label) FROM metrics", 2, SqlTypeDescriptor.BIGINT);

    assertEquals(StatusCode.OK, fixture.session.execute(
        "SELECT SUM(amount) FROM metrics WHERE id<0", result));
    assertTrue(result.hasValue());
    assertTrue(result.isNull(0));
    assertEquals(StatusCode.OK, fixture.session.execute(
        "SELECT AVG(units) FROM metrics WHERE id=3", result));
    assertTrue(result.hasValue());
    assertTrue(result.isNull(0));
    assertEquals(StatusCode.OK, fixture.session.execute(
        "SELECT SUM(amount) FROM metrics HAVING SUM(amount)>40.00", result));
    assertFalse(result.hasValue());
    fixture.close();
  }

  @Test
  void streamsMultipleScalarOutputsAndReportsDecimalOverflow(@TempDir Path root) {
    Fixture fixture = open(root);
    SqlExecutionResult result = fixture.result;
    assertEquals(StatusCode.OK, fixture.session.execute(
        "CREATE TABLE values_table (id BIGINT PRIMARY KEY,amount DECIMAL(18,0),"
            + "label VARCHAR(16))", result));
    assertEquals(StatusCode.OK, fixture.session.execute(
        "INSERT INTO values_table VALUES (1,10,'zeta'),(2,20,'alpha'),(3,NULL,NULL)",
        result));

    SqlScanCursor cursor = new SqlScanCursor();
    SqlScanRowResult row = new SqlScanRowResult();
    assertEquals(StatusCode.OK, fixture.session.beginScan(
        "SELECT SUM(amount),AVG(amount),MIN(label),MAX(label),COUNT(amount) "
            + "FROM values_table WHERE id>=2", cursor));
    assertEquals(StatusCode.OK, fixture.session.nextScan(cursor, row));
    assertEquals(5, row.columnCount());
    assertEquals(20, row.valueAt(0));
    assertEquals(20_000_000, row.valueAt(1));
    assertEquals("alpha", text(row, 2));
    assertEquals("alpha", text(row, 3));
    assertEquals(1, row.valueAt(4));
    assertEquals(StatusCode.CONFLICT, fixture.session.nextScan(cursor, row));
    assertEquals(StatusCode.OK, fixture.session.closeScan(cursor, result));

    assertEquals(StatusCode.OK, fixture.session.execute(
        "CREATE TABLE overflow_values (id BIGINT PRIMARY KEY,amount DECIMAL(38,0))",
        result));
    assertEquals(StatusCode.OK, fixture.session.execute(
        "INSERT INTO overflow_values VALUES "
            + "(1,99999999999999999999999999999999999999),(2,1)",
        result));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, fixture.session.execute(
        "SELECT SUM(amount) FROM overflow_values", result));
    fixture.close();
  }

  private static Fixture open(Path root) {
    RelationalDatabaseOpenResult opened = new RelationalDatabaseOpenResult();
    assertEquals(StatusCode.OK,
        RelationalDatabase.create(root, DATABASE, GENERATION, 7, opened));
    SqlSessionOpenResult sessions = new SqlSessionOpenResult();
    assertEquals(StatusCode.OK, SqlSession.create(opened.database(), sessions));
    return new Fixture(opened.database(), sessions.session());
  }

  private static void assertFixed(
      Fixture fixture, String sql, long expected, int descriptor) {
    assertEquals(StatusCode.OK, fixture.session.execute(sql, fixture.result));
    assertEquals(descriptor, fixture.result.typeDescriptorAt(0));
    assertEquals(expected, fixture.result.valueAt(0));
    assertFalse(fixture.result.isNull(0));
  }

  private static void assertText(Fixture fixture, String sql, String expected) {
    assertEquals(StatusCode.OK, fixture.session.execute(sql, fixture.result));
    assertEquals(expected, text(fixture.result, 0));
  }

  private static String text(SqlExecutionResult result, int column) {
    char[] text = new char[64];
    int length = result.copyTextAt(column, text, 0);
    return new String(text, 0, length);
  }

  private static String text(SqlScanRowResult result, int column) {
    char[] text = new char[64];
    int length = result.copyTextAt(column, text, 0);
    return new String(text, 0, length);
  }

  private record Fixture(
      RelationalDatabase database, SqlSession session, SqlExecutionResult result) {
    Fixture(RelationalDatabase database, SqlSession session) {
      this(database, session, new SqlExecutionResult());
    }

    void close() {
      assertEquals(StatusCode.OK, session.close());
      assertEquals(StatusCode.OK, database.close());
    }
  }
}
