package io.riverdb.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.RiverQuery;
import io.riverdb.engine.api.RowResult;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import org.junit.jupiter.api.Test;

final class RiverNumericBoundaryTest {
  @Test
  void bindingsPublishCanonicalTypedNumericParameters() throws Exception {
    RiverJdbcParameterBindings bindings = new RiverJdbcParameterBindings(6);
    bindings.setFixed(0, SqlTypeDescriptor.SMALLINT, -7);
    bindings.setFixed(1, SqlTypeDescriptor.INTEGER, 80_000);
    bindings.setFixed(2, SqlTypeDescriptor.BIGINT, Long.MAX_VALUE);
    bindings.setFixed(3, SqlTypeDescriptor.decimal(5, 2), 12_345);
    bindings.setReal(4, -0.0f);
    bindings.setDouble(5, -2.25d);

    ParameterSet values = bindings.parameters();
    assertEquals((short) -7, values.smallintAt(0));
    assertEquals(80_000, values.integerAt(1));
    assertEquals(Long.MAX_VALUE, values.bigintAt(2));
    assertEquals(12_345, values.decimalUnscaledAt(3));
    assertEquals(0, values.valueAt(4));
    assertEquals(0.0f, values.realAt(4));
    assertEquals(-2.25d, values.doubleAt(5));

    SQLException nan = assertThrows(SQLException.class,
        () -> bindings.setReal(4, Float.NaN));
    assertEquals("22003", nan.getSQLState());
    SQLException infinity = assertThrows(SQLException.class,
        () -> bindings.setDouble(5, Double.POSITIVE_INFINITY));
    assertEquals("22003", infinity.getSQLState());
  }

  @Test
  void metadataReportsEveryNumericJdbcIdentity() throws Exception {
    RiverResultSetMetaData metadata = new RiverResultSetMetaData(new NumericQuery());
    assertColumn(metadata, 1, Types.SMALLINT, "SMALLINT", Short.class, 5, 6);
    assertColumn(metadata, 2, Types.INTEGER, "INTEGER", Integer.class, 10, 11);
    assertColumn(metadata, 3, Types.BIGINT, "BIGINT", Long.class, 19, 20);
    assertColumn(metadata, 4, Types.DECIMAL, "DECIMAL", java.math.BigDecimal.class, 8, 10);
    assertEquals(2, metadata.getScale(4));
    assertColumn(metadata, 5, Types.REAL, "REAL", Float.class, 7, 15);
    assertColumn(metadata, 6, Types.DOUBLE, "DOUBLE PRECISION", Double.class, 15, 24);
  }

  @Test
  void resultSetDecodesTypedNumericLanesInsteadOfExposingFloatBits() throws Exception {
    RiverJdbcResultSet result = new RiverJdbcResultSet(null, new NumericRowsQuery());
    assertEquals(true, result.next());
    assertEquals((short) -7, result.getShort(1));
    assertEquals(Integer.valueOf(80_000), result.getObject(2));
    assertEquals(Long.MAX_VALUE, result.getLong(3));
    assertEquals(new java.math.BigDecimal("123.45"), result.getBigDecimal(4));
    assertEquals(1.5f, result.getFloat(5));
    assertEquals(Float.valueOf(1.5f), result.getObject(5));
    assertEquals("1.5", result.getString(5));
    assertEquals(-2.25d, result.getDouble(6));
    assertEquals(Double.valueOf(-2.25d), result.getObject(6));
    assertEquals(java.math.BigDecimal.valueOf(-2.25d), result.getBigDecimal(6));
  }

  @Test
  void bindsAndReadsFullPrecisionDecimalWithoutLongNarrowing() throws Exception {
    BigDecimal expected = new BigDecimal("12345678901234567890123456789012.345678");
    RiverJdbcParameterBindings bindings = new RiverJdbcParameterBindings(1);
    bindings.setDecimal(0, expected);
    ParameterSet parameter = bindings.parameters();
    assertEquals(669_260_594_276_348_691L, parameter.decimalUnscaledHighAt(0));
    assertEquals(-4_302_749_291_975_740_594L, parameter.decimalUnscaledLowAt(0));

    RiverJdbcResultSet result = new RiverJdbcResultSet(null, new Decimal128RowsQuery());
    assertEquals(true, result.next());
    assertEquals(expected, result.getBigDecimal(1));
    assertEquals(expected.toPlainString(), result.getString(1));
    assertEquals(expected, result.getObject(1));
    assertEquals("22003", assertThrows(SQLException.class,
        () -> result.getLong(1)).getSQLState());
  }

  private static void assertColumn(
      RiverResultSetMetaData metadata,
      int column,
      int jdbcType,
      String name,
      Class<?> valueClass,
      int precision,
      int displaySize) throws SQLException {
    assertEquals(jdbcType, metadata.getColumnType(column));
    assertEquals(name, metadata.getColumnTypeName(column));
    assertEquals(valueClass.getName(), metadata.getColumnClassName(column));
    assertEquals(precision, metadata.getPrecision(column));
    assertEquals(displaySize, metadata.getColumnDisplaySize(column));
  }

  private static final class NumericQuery implements RiverQuery {
    private static final int[] TYPES = {
        SqlTypeDescriptor.SMALLINT,
        SqlTypeDescriptor.INTEGER,
        SqlTypeDescriptor.BIGINT,
        SqlTypeDescriptor.decimal(8, 2),
        SqlTypeDescriptor.REAL,
        SqlTypeDescriptor.DOUBLE
    };

    @Override public StatusCode next(RowResult result) { return StatusCode.OK; }
    @Override public StatusCode close(CommandResult result) { return StatusCode.OK; }
    @Override public boolean isActive() { return true; }
    @Override public int columnCount() { return TYPES.length; }
    @Override public CharSequence columnName(int index) { return "n" + index; }
    @Override public int columnTypeDescriptor(int index) { return TYPES[index]; }
    @Override public boolean columnIsNullable(int index) { return false; }
    @Override public long rowsReturned() { return 0; }
  }

  private static final class NumericRowsQuery implements RiverQuery {
    private boolean delivered;

    @Override
    public StatusCode next(RowResult result) {
      if (delivered) return StatusCode.OK;
      delivered = true;
      long[] values = {
          -7,
          80_000,
          Long.MAX_VALUE,
          12_345,
          SqlApproximateNumeric.realBits(1.5f),
          SqlApproximateNumeric.doubleBits(-2.25d)
      };
      return result.complete(1, values, 0, NumericQuery.TYPES, values.length);
    }

    @Override public StatusCode close(CommandResult result) { return StatusCode.OK; }
    @Override public boolean isActive() { return true; }
    @Override public int columnCount() { return NumericQuery.TYPES.length; }
    @Override public CharSequence columnName(int index) { return "n" + index; }
    @Override public int columnTypeDescriptor(int index) { return NumericQuery.TYPES[index]; }
    @Override public boolean columnIsNullable(int index) { return false; }
    @Override public long rowsReturned() { return delivered ? 1 : 0; }
  }

  private static final class Decimal128RowsQuery implements RiverQuery {
    private boolean delivered;

    @Override
    public StatusCode next(RowResult result) {
      if (delivered) return StatusCode.OK;
      delivered = true;
      return result.complete(
          1,
          new long[] {669_260_594_276_348_691L},
          new long[] {-4_302_749_291_975_740_594L},
          new long[1],
          1,
          new int[] {SqlTypeDescriptor.decimal(38, 6)},
          1);
    }

    @Override public StatusCode close(CommandResult result) { return StatusCode.OK; }
    @Override public boolean isActive() { return true; }
    @Override public int columnCount() { return 1; }
    @Override public CharSequence columnName(int index) { return "amount"; }
    @Override public int columnTypeDescriptor(int index) {
      return SqlTypeDescriptor.decimal(38, 6);
    }
    @Override public boolean columnIsNullable(int index) { return false; }
    @Override public long rowsReturned() { return delivered ? 1 : 0; }
  }
}
