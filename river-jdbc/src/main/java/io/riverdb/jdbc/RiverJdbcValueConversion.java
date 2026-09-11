package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/** Converts one admitted River scalar into its requested JDBC representation. */
final class RiverJdbcValueConversion {
  private final RiverJdbcResultSet resultSet;

  RiverJdbcValueConversion(RiverJdbcResultSet resultSet) {
    this.resultSet = resultSet;
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

  <T> T getObject(int column, Class<T> type) throws SQLException {
    if (type == null) throw JdbcExceptions.invalid("target type must not be null");
    long value = resultSet.value(column);
    requireSupported(column, type);
    if (resultSet.lastWasNull()) return null;
    Object converted;
    if (resultSet.metadata().isVarchar(column)) {
      converted = stringValue(column, value);
    } else if (RiverJdbcTemporalValues.isTemporal(
        resultSet.metadata().typeDescriptor(column))) {
      converted = RiverJdbcTemporalValues.convert(
          value, resultSet.metadata().typeDescriptor(column), type,
          resultSet.textCharacters());
    } else if (resultSet.metadata().isBoolean(column)
        && (type == Boolean.class || type == Boolean.TYPE)) {
      converted = Boolean.valueOf(value != 0);
    } else if (type == Short.class || type == Short.TYPE) {
      converted = Short.valueOf(checkedShort(integralNumericValue(column, value)));
    } else if (type == Integer.class || type == Integer.TYPE) {
      converted = Integer.valueOf(checkedInt(integralNumericValue(column, value)));
    } else if (type == Long.class || type == Long.TYPE) {
      converted = Long.valueOf(integralNumericValue(column, value));
    } else if (type == Float.class || type == Float.TYPE) {
      double numeric = floatingNumericValue(column, value);
      converted = Float.valueOf(checkedFloat(numeric));
    } else if (type == Double.class || type == Double.TYPE) {
      converted = Double.valueOf(floatingNumericValue(column, value));
    } else if (type == String.class) {
      converted = stringValue(column, value);
    } else if (type == BigDecimal.class) {
      converted = bigDecimalValue(column, value);
    } else {
      throw JdbcExceptions.unsupported();
    }
    @SuppressWarnings("unchecked")
    T result = (T) converted;
    return result;
  }

  Object getObject(int column) throws SQLException {
    long value = resultSet.value(column);
    if (resultSet.lastWasNull()) return null;
    if (resultSet.metadata().isVarchar(column)) return stringValue(column, value);
    if (resultSet.metadata().isDecimal(column)) return decimalValue(column, value);
    int descriptor = resultSet.metadata().typeDescriptor(column);
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (type == SqlTypeDescriptor.TYPE_ID_SMALLINT) return Short.valueOf((short) value);
    if (type == SqlTypeDescriptor.TYPE_ID_INTEGER) return Integer.valueOf((int) value);
    if (type == SqlTypeDescriptor.TYPE_ID_REAL) {
      return Float.valueOf(Float.intBitsToFloat((int) value));
    }
    if (type == SqlTypeDescriptor.TYPE_ID_DOUBLE) {
      return Double.valueOf(Double.longBitsToDouble(value));
    }
    if (RiverJdbcTemporalValues.isTemporal(descriptor)) {
      return RiverJdbcTemporalValues.object(value, descriptor);
    }
    return resultSet.metadata().isBoolean(column)
        ? Boolean.valueOf(value != 0) : Long.valueOf(value);
  }

  Date getDate(int column) throws SQLException {
    long value = resultSet.value(column);
    int descriptor = resultSet.metadata().typeDescriptor(column);
    if (!RiverJdbcTemporalValues.supportsObjectClass(descriptor, Date.class)) {
      throw JdbcExceptions.unsupported();
    }
    return resultSet.lastWasNull() ? null : RiverJdbcTemporalValues.date(value, descriptor);
  }

  Time getTime(int column) throws SQLException {
    long value = resultSet.value(column);
    int descriptor = resultSet.metadata().typeDescriptor(column);
    if (!RiverJdbcTemporalValues.supportsObjectClass(descriptor, Time.class)) {
      throw JdbcExceptions.unsupported();
    }
    return resultSet.lastWasNull() ? null : RiverJdbcTemporalValues.time(value, descriptor);
  }

  Timestamp getTimestamp(int column) throws SQLException {
    long value = resultSet.value(column);
    int descriptor = resultSet.metadata().typeDescriptor(column);
    if (!RiverJdbcTemporalValues.supportsObjectClass(descriptor, Timestamp.class)) {
      throw JdbcExceptions.unsupported();
    }
    return resultSet.lastWasNull()
        ? null : RiverJdbcTemporalValues.timestamp(value, descriptor);
  }

  private String stringValue(int column, long value) throws SQLException {
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

  private void requireSupported(int column, Class<?> type) throws SQLException {
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

  private long numericValue(int column) throws SQLException {
    long value = resultSet.value(column);
    checkNumeric(column);
    return value;
  }

  private long integralNumericValue(int column) throws SQLException {
    return integralNumericValue(column, numericValue(column));
  }

  private long integralNumericValue(int column, long value) throws SQLException {
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

  private double floatingNumericValue(int column, long value) throws SQLException {
    if (resultSet.lastWasNull()) return 0.0d;
    return resultSet.metadata().isDecimal(column)
        ? decimalValue(column, value).doubleValue()
        : resultSet.metadata().isApproximate(column)
            ? approximateValue(value, resultSet.metadata().typeDescriptor(column)) : value;
  }

  private BigDecimal bigDecimalValue(int column, long value) throws SQLException {
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

  private BigDecimal decimalValue(int column, long low) throws SQLException {
    return RiverJdbcDecimal128.value(
        resultSet.decimalUnscaledHigh(column), low, resultSet.metadata().decimalScale(column));
  }

  private SQLException numericOverflow() {
    return JdbcExceptions.failure(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "convert numeric result");
  }

  private short checkedShort(long value) throws SQLException {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) throw numericOverflow();
    return (short) value;
  }

  private int checkedInt(long value) throws SQLException {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw numericOverflow();
    return (int) value;
  }

  private float checkedFloat(double value) throws SQLException {
    if (value > Float.MAX_VALUE || value < -Float.MAX_VALUE) throw numericOverflow();
    return (float) value;
  }

  private static boolean numericTarget(Class<?> type) {
    return type == Short.class || type == Short.TYPE
        || type == Integer.class || type == Integer.TYPE
        || type == Long.class || type == Long.TYPE
        || type == Float.class || type == Float.TYPE
        || type == Double.class || type == Double.TYPE
        || type == BigDecimal.class || type == String.class;
  }
}
