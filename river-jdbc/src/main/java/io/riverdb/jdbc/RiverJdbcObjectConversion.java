package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/** Converts one admitted River scalar into an object requested by a JDBC caller. */
final class RiverJdbcObjectConversion {
  private RiverJdbcObjectConversion() { }

  static <T> T getObject(
      RiverJdbcScalarConversion scalarConversion, int column, Class<T> type) throws SQLException {
    if (type == null) throw JdbcExceptions.invalid("target type must not be null");
    RiverJdbcResultSet resultSet = scalarConversion.resultSet();
    long value = resultSet.value(column);
    scalarConversion.requireObjectType(column, type);
    if (resultSet.lastWasNull()) return null;
    Object converted;
    if (resultSet.metadata().isVarchar(column)) {
      converted = scalarConversion.stringValue(column, value);
    } else if (RiverJdbcTemporalValues.isTemporal(
        resultSet.metadata().typeDescriptor(column))) {
      converted = RiverJdbcTemporalValues.convert(
          value, resultSet.metadata().typeDescriptor(column), type,
          resultSet.textCharacters());
    } else if (resultSet.metadata().isBoolean(column)
        && (type == Boolean.class || type == Boolean.TYPE)) {
      converted = Boolean.valueOf(value != 0);
    } else if (type == Short.class || type == Short.TYPE) {
      converted = Short.valueOf(
          scalarConversion.checkedShort(scalarConversion.integralNumericValue(column, value)));
    } else if (type == Integer.class || type == Integer.TYPE) {
      converted = Integer.valueOf(
          scalarConversion.checkedInt(scalarConversion.integralNumericValue(column, value)));
    } else if (type == Long.class || type == Long.TYPE) {
      converted = Long.valueOf(scalarConversion.integralNumericValue(column, value));
    } else if (type == Float.class || type == Float.TYPE) {
      converted = Float.valueOf(scalarConversion.checkedFloat(
          scalarConversion.floatingNumericValue(column, value)));
    } else if (type == Double.class || type == Double.TYPE) {
      converted = Double.valueOf(scalarConversion.floatingNumericValue(column, value));
    } else if (type == String.class) {
      converted = scalarConversion.stringValue(column, value);
    } else if (type == BigDecimal.class) {
      converted = scalarConversion.bigDecimalValue(column, value);
    } else {
      throw JdbcExceptions.unsupported();
    }
    @SuppressWarnings("unchecked")
    T result = (T) converted;
    return result;
  }

  static Date getDate(RiverJdbcScalarConversion scalarConversion, int column)
      throws SQLException {
    RiverJdbcResultSet resultSet = scalarConversion.resultSet();
    long value = resultSet.value(column);
    int descriptor = resultSet.metadata().typeDescriptor(column);
    if (!RiverJdbcTemporalValues.supportsObjectClass(descriptor, Date.class)) {
      throw JdbcExceptions.unsupported();
    }
    return resultSet.lastWasNull() ? null : RiverJdbcTemporalValues.date(value, descriptor);
  }

  static Time getTime(RiverJdbcScalarConversion scalarConversion, int column)
      throws SQLException {
    RiverJdbcResultSet resultSet = scalarConversion.resultSet();
    long value = resultSet.value(column);
    int descriptor = resultSet.metadata().typeDescriptor(column);
    if (!RiverJdbcTemporalValues.supportsObjectClass(descriptor, Time.class)) {
      throw JdbcExceptions.unsupported();
    }
    return resultSet.lastWasNull() ? null : RiverJdbcTemporalValues.time(value, descriptor);
  }

  static Timestamp getTimestamp(RiverJdbcScalarConversion scalarConversion, int column)
      throws SQLException {
    RiverJdbcResultSet resultSet = scalarConversion.resultSet();
    long value = resultSet.value(column);
    int descriptor = resultSet.metadata().typeDescriptor(column);
    if (!RiverJdbcTemporalValues.supportsObjectClass(descriptor, Timestamp.class)) {
      throw JdbcExceptions.unsupported();
    }
    return resultSet.lastWasNull()
        ? null : RiverJdbcTemporalValues.timestamp(value, descriptor);
  }

  static Object getObject(RiverJdbcScalarConversion scalarConversion, int column)
      throws SQLException {
    RiverJdbcResultSet resultSet = scalarConversion.resultSet();
    long value = resultSet.value(column);
    if (resultSet.lastWasNull()) return null;
    if (resultSet.metadata().isVarchar(column)) {
      return scalarConversion.stringValue(column, value);
    }
    if (resultSet.metadata().isDecimal(column)) {
      return scalarConversion.decimalValue(column, value);
    }
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

}
