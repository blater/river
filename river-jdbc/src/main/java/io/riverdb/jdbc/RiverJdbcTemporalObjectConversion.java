package io.riverdb.jdbc;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** Reuses one primitive temporal result across supported JDBC/java.time objects. */
final class RiverJdbcTemporalObjectConversion {
  private final RiverJdbcParameterBindings bindings;
  private final RiverJdbcTemporalParameters.Value temporal =
      new RiverJdbcTemporalParameters.Value();

  RiverJdbcTemporalObjectConversion(RiverJdbcParameterBindings parameterBindings) {
    bindings = parameterBindings;
  }

  void infer(int index, Object value) throws SQLException {
    if (value instanceof Date date) RiverJdbcTemporalParameters.date(date, temporal);
    else if (value instanceof Time time) RiverJdbcTemporalParameters.time(time, temporal);
    else if (value instanceof Timestamp timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else if (value instanceof LocalDate date) RiverJdbcTemporalParameters.date(date, temporal);
    else if (value instanceof LocalTime time) RiverJdbcTemporalParameters.time(time, temporal);
    else if (value instanceof LocalDateTime timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else if (value instanceof OffsetDateTime timestamp) {
      RiverJdbcTemporalParameters.timestampWithZone(timestamp, temporal);
    } else if (value instanceof Instant instant) {
      RiverJdbcTemporalParameters.instant(instant, temporal);
    } else throw JdbcExceptions.unsupported();
    publish(index);
  }

  void date(int index, Object value) throws SQLException {
    if (value instanceof Date date) RiverJdbcTemporalParameters.date(date, temporal);
    else if (value instanceof LocalDate date) RiverJdbcTemporalParameters.date(date, temporal);
    else throw JdbcExceptions.unsupported();
    publish(index);
  }

  void date(int index, Date value) throws SQLException {
    RiverJdbcTemporalParameters.date(value, temporal);
    publish(index);
  }

  void time(int index, Object value) throws SQLException {
    if (value instanceof Time time) RiverJdbcTemporalParameters.time(time, temporal);
    else if (value instanceof LocalTime time) RiverJdbcTemporalParameters.time(time, temporal);
    else throw JdbcExceptions.unsupported();
    publish(index);
  }

  void time(int index, Time value) throws SQLException {
    RiverJdbcTemporalParameters.time(value, temporal);
    publish(index);
  }

  void timestamp(int index, Object value) throws SQLException {
    if (value instanceof Timestamp timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else if (value instanceof LocalDateTime timestamp) {
      RiverJdbcTemporalParameters.timestamp(timestamp, temporal);
    } else throw JdbcExceptions.unsupported();
    publish(index);
  }

  void timestamp(int index, Timestamp value) throws SQLException {
    RiverJdbcTemporalParameters.timestamp(value, temporal);
    publish(index);
  }

  void zoned(int index, Object value) throws SQLException {
    if (value instanceof OffsetDateTime timestamp) {
      RiverJdbcTemporalParameters.timestampWithZone(timestamp, temporal);
    } else if (value instanceof Instant instant) {
      RiverJdbcTemporalParameters.instant(instant, temporal);
    } else throw JdbcExceptions.unsupported();
    publish(index);
  }

  private void publish(int index) throws SQLException {
    if (temporal.isNull()) bindings.setNull(index, temporal.descriptor());
    else bindings.setFixed(index, temporal.descriptor(), temporal.value());
  }
}
