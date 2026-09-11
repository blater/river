package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigDecimal;
import java.sql.SQLException;

/** Converts one admitted River scalar into a primitive or text JDBC value. */
final class RiverJdbcScalarConversion {
  private static final Class<?>[] NUMERIC_TARGETS = {
      Short.class, Short.TYPE, Integer.class, Integer.TYPE,
      Long.class, Long.TYPE, Float.class, Float.TYPE,
      Double.class, Double.TYPE, BigDecimal.class, String.class
  };
  private final RiverJdbcResultSet resultSet;

  RiverJdbcScalarConversion(RiverJdbcResultSet resultSet) {
    this.resultSet = resultSet;
  }

  RiverJdbcResultSet resultSet() {
    return resultSet;
  }

  void requireObjectType(int column, Class<?> type) throws SQLException {
    int descriptor = resultSet.metadata().typeDescriptor(column);
    boolean supported = switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT,
          SqlTypeDescriptor.TYPE_ID_INTEGER,
          SqlTypeDescriptor.TYPE_ID_BIGINT,
          SqlTypeDescriptor.TYPE_ID_REAL,
          SqlTypeDescriptor.TYPE_ID_DOUBLE -> numericTarget(type);
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> type == Boolean.class
          || type == Boolean.TYPE || type == Long.class || type == Long.TYPE
          || type == Integer.class || type == Integer.TYPE
          || type == BigDecimal.class || type == String.class;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          type == BigDecimal.class || type == String.class;
      case SqlTypeDescriptor.TYPE_ID_VARCHAR -> type == String.class;
      default -> RiverJdbcTemporalValues.supportsObjectClass(descriptor, type);
    };
    if (!supported) throw JdbcExceptions.unsupported();
  }

  String getString(int column) throws SQLException {
    long value = resultSet.value(column);
    return stringValue(column, value);
  }

  boolean getBoolean(int column) throws SQLException {
    long value = numericValue(column);
    if (resultSet.lastWasNull()) return false;
    if (resultSet.metadata().isDecimal(column)) return decimalValue(column, value).signum() != 0;
    return resultSet.metadata().isApproximate(column)
        ? approximateValue(value, resultSet.metadata().typeDescriptor(column)) != 0.0d
        : value != 0;
  }

  byte getByte(int column) throws SQLException {
    long value = integralNumericValue(column);
    if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) throw numericOverflow();
    return (byte) value;
  }

  short getShort(int column) throws SQLException {
    return checkedShort(integralNumericValue(column));
  }

  int getInt(int column) throws SQLException {
    return checkedInt(integralNumericValue(column));
  }

  long getLong(int column) throws SQLException {
    return integralNumericValue(column);
  }

  float getFloat(int column) throws SQLException {
    return checkedFloat(floatingNumericValue(column));
  }

  double getDouble(int column) throws SQLException {
    return floatingNumericValue(column);
  }

  BigDecimal getBigDecimal(int column) throws SQLException {
    long value = numericValue(column);
    return resultSet.lastWasNull() ? null : bigDecimalValue(column, value);
  }

  String stringValue(int column, long value) throws SQLException {
    if (resultSet.lastWasNull()) return null;
    if (resultSet.metadata().isBoolean(column)) return Boolean.toString(value != 0);
    if (resultSet.metadata().isDecimal(column)) {
      return decimalValue(column, value).toPlainString();
    }
    int descriptor = resultSet.metadata().typeDescriptor(column);
    if (resultSet.metadata().isApproximate(column)) {
      return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_REAL
          ? Float.toString(Float.intBitsToFloat((int) value))
          : Double.toString(Double.longBitsToDouble(value));
    }
    if (RiverJdbcTemporalValues.isTemporal(descriptor)) {
      return RiverJdbcTemporalValues.string(
          value, descriptor, resultSet.textCharacters());
    }
    if (!resultSet.metadata().isVarchar(column)) return Long.toString(value);
    int bytes = resultSet.textLength(column);
    char[] characters = resultSet.textCharacters(bytes);
    int length = resultSet.copyText(column, characters, 0);
    if (length < 0) throw JdbcExceptions.invalid("VARCHAR value is invalid");
    return new String(characters, 0, length);
  }

  private long numericValue(int column) throws SQLException {
    long value = resultSet.value(column);
    checkNumeric(column);
    return value;
  }

  private long integralNumericValue(int column) throws SQLException {
    return integralNumericValue(column, numericValue(column));
  }

  long integralNumericValue(int column, long value) throws SQLException {
    if (resultSet.lastWasNull()) return 0;
    if (!resultSet.metadata().isDecimal(column)) {
      if (!resultSet.metadata().isApproximate(column)) return value;
      double converted = approximateValue(value, resultSet.metadata().typeDescriptor(column));
      if (converted < Long.MIN_VALUE || converted > Long.MAX_VALUE) throw numericOverflow();
      return (long) converted;
    }
    try {
      return decimalValue(column, value).longValueExact();
    } catch (ArithmeticException failure) {
      throw numericOverflow();
    }
  }

  private double floatingNumericValue(int column) throws SQLException {
    return floatingNumericValue(column, numericValue(column));
  }

  double floatingNumericValue(int column, long value) throws SQLException {
    if (resultSet.lastWasNull()) return 0.0d;
    return resultSet.metadata().isDecimal(column)
        ? decimalValue(column, value).doubleValue()
        : resultSet.metadata().isApproximate(column)
            ? approximateValue(value, resultSet.metadata().typeDescriptor(column)) : value;
  }

  BigDecimal bigDecimalValue(int column, long value) throws SQLException {
    if (resultSet.metadata().isDecimal(column)) return decimalValue(column, value);
    return resultSet.metadata().isApproximate(column)
        ? BigDecimal.valueOf(approximateValue(value, resultSet.metadata().typeDescriptor(column)))
        : BigDecimal.valueOf(value);
  }

  private void checkNumeric(int column) throws SQLException {
    if (resultSet.metadata().isVarchar(column)
        || RiverJdbcTemporalValues.isTemporal(resultSet.metadata().typeDescriptor(column))) {
      throw JdbcExceptions.unsupported();
    }
  }

  private double approximateValue(long bits, int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_REAL
        ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
  }

  BigDecimal decimalValue(int column, long low) throws SQLException {
    return RiverJdbcDecimal128.value(
        resultSet.decimalUnscaledHigh(column), low, resultSet.metadata().decimalScale(column));
  }

  private SQLException numericOverflow() {
    return JdbcExceptions.failure(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "convert numeric result");
  }

  short checkedShort(long value) throws SQLException {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) throw numericOverflow();
    return (short) value;
  }

  int checkedInt(long value) throws SQLException {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw numericOverflow();
    return (int) value;
  }

  float checkedFloat(double value) throws SQLException {
    if (value > Float.MAX_VALUE || value < -Float.MAX_VALUE) throw numericOverflow();
    return (float) value;
  }

  private static boolean numericTarget(Class<?> type) {
    for (Class<?> target : NUMERIC_TARGETS) {
      if (target == type) return true;
    }
    return false;
  }

}
