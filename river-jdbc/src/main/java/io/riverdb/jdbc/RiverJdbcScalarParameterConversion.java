package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.math.BigDecimal;
import java.sql.SQLException;

/** Explicit scalar JDBC object conversions. */
final class RiverJdbcScalarParameterConversion {
  private final RiverJdbcParameterBindings bindings;

  RiverJdbcScalarParameterConversion(RiverJdbcParameterBindings parameterBindings) {
    bindings = parameterBindings;
  }

  void integral(int index, Object value, int descriptor) throws SQLException {
    if (!(value instanceof Byte || value instanceof Short
        || value instanceof Integer || value instanceof Long)) {
      throw JdbcExceptions.unsupported();
    }
    long converted = ((Number) value).longValue();
    if (!SqlValueDomain.validFixed(descriptor, converted)) {
      throw JdbcExceptions.failure(
          StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, "set integral parameter");
    }
    bindings.setFixed(index, descriptor, converted);
  }

  void real(int index, Object value) throws SQLException {
    if (value instanceof Number number) bindings.setReal(index, number.floatValue());
    else throw JdbcExceptions.unsupported();
  }

  void doubleValue(int index, Object value) throws SQLException {
    if (value instanceof Number number) bindings.setDouble(index, number.doubleValue());
    else throw JdbcExceptions.unsupported();
  }

  void bool(int index, Object value) throws SQLException {
    if (value instanceof Boolean bool) {
      bindings.setFixed(index, SqlTypeDescriptor.BOOLEAN, bool.booleanValue() ? 1 : 0);
    } else throw JdbcExceptions.unsupported();
  }

  void decimal(int index, Object value) throws SQLException {
    if (value instanceof BigDecimal decimal) bindings.setDecimal(index, decimal);
    else throw JdbcExceptions.unsupported();
  }

  void text(int index, Object value) throws SQLException {
    if (value instanceof String text) bindings.setText(index, text);
    else throw JdbcExceptions.unsupported();
  }
}
