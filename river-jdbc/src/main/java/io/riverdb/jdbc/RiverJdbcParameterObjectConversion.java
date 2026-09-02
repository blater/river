package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.sql.SQLException;
import java.sql.Types;

/** Dispatches explicit JDBC target types to scalar or temporal conversion. */
final class RiverJdbcParameterObjectConversion {
  private final RiverJdbcParameterBindings bindings;
  private final RiverJdbcScalarParameterConversion scalar;
  private final RiverJdbcTemporalObjectConversion temporal;

  RiverJdbcParameterObjectConversion(
      RiverJdbcParameterBindings parameterBindings,
      RiverJdbcTemporalObjectConversion temporalConversion) {
    bindings = parameterBindings;
    scalar = new RiverJdbcScalarParameterConversion(parameterBindings);
    temporal = temporalConversion;
  }

  void set(int index, Object value, int targetType) throws SQLException {
    if (value == null) {
      bindings.setNull(index, RiverJdbcParameterTypes.nullDescriptor(targetType));
      return;
    }
    switch (targetType) {
      case Types.SMALLINT, Types.TINYINT ->
          scalar.integral(index, value, SqlTypeDescriptor.SMALLINT);
      case Types.INTEGER -> scalar.integral(index, value, SqlTypeDescriptor.INTEGER);
      case Types.BIGINT -> scalar.integral(index, value, SqlTypeDescriptor.BIGINT);
      case Types.REAL, Types.FLOAT -> scalar.real(index, value);
      case Types.DOUBLE -> scalar.doubleValue(index, value);
      case Types.BOOLEAN -> scalar.bool(index, value);
      case Types.DECIMAL, Types.NUMERIC -> scalar.decimal(index, value);
      case Types.VARCHAR, Types.CHAR -> scalar.text(index, value);
      case Types.DATE -> temporal.date(index, value);
      case Types.TIME -> temporal.time(index, value);
      case Types.TIMESTAMP -> temporal.timestamp(index, value);
      case Types.TIMESTAMP_WITH_TIMEZONE -> temporal.zoned(index, value);
      default -> throw JdbcExceptions.unsupported();
    }
  }

  void date(int index, java.sql.Date value) throws SQLException {
    temporal.date(index, value);
  }

  void time(int index, java.sql.Time value) throws SQLException {
    temporal.time(index, value);
  }

  void timestamp(int index, java.sql.Timestamp value) throws SQLException {
    temporal.timestamp(index, value);
  }
}
