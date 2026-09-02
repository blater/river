package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigDecimal;
import java.sql.SQLException;

/** Converts one River scalar into the JDBC target class requested by a caller. */
final class RiverJdbcObjectConversion {
  private final RiverJdbcResultSet resultSet;

  RiverJdbcObjectConversion(RiverJdbcResultSet resultSet) {
    this.resultSet = resultSet;
  }

  <T> T getObject(int column, Class<T> type) throws SQLException {
    if (type == null) throw JdbcExceptions.invalid("target type must not be null");
    long value = resultSet.value(column);
    requireSupported(column, type);
    if (resultSet.lastWasNull()) return null;
    Object converted;
    if (resultSet.metadata().isVarchar(column)) {
      if (type != String.class) throw JdbcExceptions.unsupported();
      converted = resultSet.getString(column);
    } else if (RiverJdbcTemporalValues.isTemporal(
        resultSet.metadata().typeDescriptor(column))) {
      converted = RiverJdbcTemporalValues.convert(
          value, resultSet.metadata().typeDescriptor(column), type,
          resultSet.textCharacters());
    } else if (resultSet.metadata().isBoolean(column)
        && (type == Boolean.class || type == Boolean.TYPE)) {
      converted = Boolean.valueOf(value != 0);
    } else if (type == Short.class || type == Short.TYPE) {
      converted = Short.valueOf(resultSet.getShort(column));
    } else if (type == Integer.class || type == Integer.TYPE) {
      converted = Integer.valueOf(resultSet.getInt(column));
    } else if (type == Long.class || type == Long.TYPE) {
      converted = Long.valueOf(resultSet.getLong(column));
    } else if (type == Float.class || type == Float.TYPE) {
      converted = Float.valueOf(resultSet.getFloat(column));
    } else if (type == Double.class || type == Double.TYPE) {
      converted = Double.valueOf(resultSet.getDouble(column));
    } else if (type == String.class) {
      converted = stringValue(column, value);
    } else if (type == BigDecimal.class) {
      converted = resultSet.getBigDecimal(column);
    } else {
      throw JdbcExceptions.unsupported();
    }
    @SuppressWarnings("unchecked")
    T result = (T) converted;
    return result;
  }

  private Object stringValue(int column, long value) throws SQLException {
    if (resultSet.metadata().isBoolean(column)) return Boolean.toString(value != 0);
    return resultSet.getString(column);
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

  private static boolean numericTarget(Class<?> type) {
    return type == Short.class || type == Short.TYPE
        || type == Integer.class || type == Integer.TYPE
        || type == Long.class || type == Long.TYPE
        || type == Float.class || type == Float.TYPE
        || type == Double.class || type == Double.TYPE
        || type == BigDecimal.class || type == String.class;
  }
}
