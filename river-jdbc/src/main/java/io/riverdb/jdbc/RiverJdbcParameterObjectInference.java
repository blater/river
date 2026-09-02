package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigDecimal;
import java.sql.SQLException;

/** Infers a River SQL type from the supported JDBC object families. */
final class RiverJdbcParameterObjectInference {
  private final RiverJdbcParameterBindings bindings;
  private final RiverJdbcTemporalObjectConversion temporal;

  RiverJdbcParameterObjectInference(
      RiverJdbcParameterBindings parameterBindings,
      RiverJdbcTemporalObjectConversion temporalConversion) {
    bindings = parameterBindings;
    temporal = temporalConversion;
  }

  void set(int index, Object value) throws SQLException {
    if (value == null) bindings.setNull(index, 0);
    else if (value instanceof Byte number) {
      bindings.setFixed(index, SqlTypeDescriptor.SMALLINT, number.longValue());
    } else if (value instanceof Short number) {
      bindings.setFixed(index, SqlTypeDescriptor.SMALLINT, number.longValue());
    } else if (value instanceof Integer number) {
      bindings.setFixed(index, SqlTypeDescriptor.INTEGER, number.longValue());
    } else if (value instanceof Long number) {
      bindings.setFixed(index, SqlTypeDescriptor.BIGINT, number.longValue());
    } else if (value instanceof Float number) bindings.setReal(index, number.floatValue());
    else if (value instanceof Double number) bindings.setDouble(index, number.doubleValue());
    else if (value instanceof Boolean bool) {
      bindings.setFixed(index, SqlTypeDescriptor.BOOLEAN, bool.booleanValue() ? 1 : 0);
    } else if (value instanceof BigDecimal decimal) bindings.setDecimal(index, decimal);
    else if (value instanceof String text) bindings.setText(index, text);
    else temporal.infer(index, value);
  }
}
