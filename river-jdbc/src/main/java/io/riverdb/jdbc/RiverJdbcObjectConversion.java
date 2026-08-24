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
    } else if (type == Long.class || type == Long.TYPE) {
      converted = Long.valueOf(value);
    } else if (type == Integer.class || type == Integer.TYPE) {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw resultSet.numericOverflow();
      }
      converted = Integer.valueOf((int) value);
    } else if (type == String.class) {
      converted = stringValue(column, value);
    } else if (type == BigDecimal.class) {
      converted = BigDecimal.valueOf(
          value, resultSet.metadata().isDecimal(column)
              ? resultSet.metadata().decimalScale(column) : 0);
    } else {
      throw JdbcExceptions.unsupported();
    }
    @SuppressWarnings("unchecked")
    T result = (T) converted;
    return result;
  }

  private Object stringValue(int column, long value) throws SQLException {
    if (resultSet.metadata().isBoolean(column)) return Boolean.toString(value != 0);
    if (resultSet.metadata().isDecimal(column)) {
      return BigDecimal.valueOf(
          value, resultSet.metadata().decimalScale(column)).toPlainString();
    }
    return Long.toString(value);
  }

  private void requireSupported(int column, Class<?> type) throws SQLException {
    int descriptor = resultSet.metadata().typeDescriptor(column);
    boolean supported = switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> type == Long.class
          || type == Long.TYPE || type == Integer.class || type == Integer.TYPE
          || type == BigDecimal.class || type == String.class;
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
}
