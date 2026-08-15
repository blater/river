package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** Maps supported JDBC objects into one statement's primitive bindings. */
final class RiverJdbcParameterObjects {
  private final RiverJdbcParameterBindings bindings;
  private final RiverJdbcTemporalParameters.Value temporal =
      new RiverJdbcTemporalParameters.Value();

  RiverJdbcParameterObjects(RiverJdbcParameterBindings parameterBindings) {
    bindings = parameterBindings;
  }

  void date(int index, Date value) throws SQLException {
    RiverJdbcTemporalParameters.date(value, temporal);
    publishTemporal(index);
  }

  void time(int index, Time value) throws SQLException {
    RiverJdbcTemporalParameters.time(value, temporal);
    publishTemporal(index);
  }

  void timestamp(int index, Timestamp value) throws SQLException {
    RiverJdbcTemporalParameters.timestamp(value, temporal);
    publishTemporal(index);
  }

  void set(int index, Object value) throws SQLException {
    if (value == null) {
      bindings.setNull(index, 0);
    } else if (value instanceof Byte number) {
      bindings.setFixed(index, SqlTypeDescriptor.BIGINT, number.longValue());
    } else if (value instanceof Short number) {
      bindings.setFixed(index, SqlTypeDescriptor.BIGINT, number.longValue());
    } else if (value instanceof Integer number) {
      bindings.setFixed(index, SqlTypeDescriptor.BIGINT, number.longValue());
    } else if (value instanceof Long number) {
      bindings.setFixed(index, SqlTypeDescriptor.BIGINT, number.longValue());
    } else if (value instanceof Boolean bool) {
      bindings.setFixed(index, SqlTypeDescriptor.BOOLEAN,
          bool.booleanValue() ? 1 : 0);
    } else if (value instanceof BigDecimal decimal) {
      bindings.setDecimal(index, decimal);
    } else if (value instanceof String text) {
      bindings.setText(index, text);
    } else {
      temporal(index, value);
    }
  }

  void set(int index, Object value, int targetType) throws SQLException {
    if (value == null) {
      bindings.setNull(index, RiverJdbcParameterTypes.nullDescriptor(targetType));
      return;
    }
    switch (targetType) {
      case Types.BIGINT -> integral(index, value);
      case Types.BOOLEAN -> bool(index, value);
      case Types.DECIMAL, Types.NUMERIC -> decimal(index, value);
      case Types.VARCHAR, Types.CHAR -> text(index, value);
      case Types.DATE -> date(index, value);
      case Types.TIME -> time(index, value);
      case Types.TIMESTAMP -> timestamp(index, value);
      case Types.TIMESTAMP_WITH_TIMEZONE -> zoned(index, value);
      default -> throw JdbcExceptions.unsupported();
    }
  }

  private void temporal(int index, Object value) throws SQLException {
    if (value instanceof Date date) RiverJdbcTemporalParameters.date(date, temporal);
    else if (value instanceof Time time) RiverJdbcTemporalParameters.time(time, temporal);
    else if (value instanceof Timestamp timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else if (value instanceof LocalDate date) {
      RiverJdbcTemporalParameters.date(date, temporal);
    } else if (value instanceof LocalTime time) {
      RiverJdbcTemporalParameters.time(time, temporal);
    } else if (value instanceof LocalDateTime timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else if (value instanceof OffsetDateTime timestamp) {
      RiverJdbcTemporalParameters.timestampWithZone(timestamp, temporal);
    } else if (value instanceof Instant instant) {
      RiverJdbcTemporalParameters.instant(instant, temporal);
    } else {
      throw JdbcExceptions.unsupported();
    }
    publishTemporal(index);
  }

  private void integral(int index, Object value) throws SQLException {
    if (value instanceof Byte number) setLong(index, number.longValue());
    else if (value instanceof Short number) setLong(index, number.longValue());
    else if (value instanceof Integer number) setLong(index, number.longValue());
    else if (value instanceof Long number) setLong(index, number.longValue());
    else throw JdbcExceptions.unsupported();
  }

  private void bool(int index, Object value) throws SQLException {
    if (value instanceof Boolean bool) {
      bindings.setFixed(index, SqlTypeDescriptor.BOOLEAN,
          bool.booleanValue() ? 1 : 0);
    } else throw JdbcExceptions.unsupported();
  }

  private void decimal(int index, Object value) throws SQLException {
    if (value instanceof BigDecimal decimal) bindings.setDecimal(index, decimal);
    else throw JdbcExceptions.unsupported();
  }

  private void text(int index, Object value) throws SQLException {
    if (value instanceof String text) bindings.setText(index, text);
    else throw JdbcExceptions.unsupported();
  }

  private void date(int index, Object value) throws SQLException {
    if (value instanceof Date date) RiverJdbcTemporalParameters.date(date, temporal);
    else if (value instanceof LocalDate date) {
      RiverJdbcTemporalParameters.date(date, temporal);
    } else throw JdbcExceptions.unsupported();
    publishTemporal(index);
  }

  private void time(int index, Object value) throws SQLException {
    if (value instanceof Time time) RiverJdbcTemporalParameters.time(time, temporal);
    else if (value instanceof LocalTime time) {
      RiverJdbcTemporalParameters.time(time, temporal);
    } else throw JdbcExceptions.unsupported();
    publishTemporal(index);
  }

  private void timestamp(int index, Object value) throws SQLException {
    if (value instanceof Timestamp timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else if (value instanceof LocalDateTime timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else throw JdbcExceptions.unsupported();
    publishTemporal(index);
  }

  private void zoned(int index, Object value) throws SQLException {
    if (value instanceof OffsetDateTime timestamp) {
      RiverJdbcTemporalParameters.timestampWithZone(timestamp, temporal);
    } else if (value instanceof Instant instant) {
      RiverJdbcTemporalParameters.instant(instant, temporal);
    } else throw JdbcExceptions.unsupported();
    publishTemporal(index);
  }

  private void setLong(int index, long value) throws SQLException {
    bindings.setFixed(index, SqlTypeDescriptor.BIGINT, value);
  }

  private void publishTemporal(int index) throws SQLException {
    if (temporal.isNull()) bindings.setNull(index, temporal.descriptor());
    else bindings.setFixed(index, temporal.descriptor(), temporal.value());
  }
}
