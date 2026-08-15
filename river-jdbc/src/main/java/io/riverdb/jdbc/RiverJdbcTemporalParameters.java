package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** Converts JDBC temporal inputs into River's primitive typed representation. */
final class RiverJdbcTemporalParameters {
  private RiverJdbcTemporalParameters() {
  }

  static void date(Date value, Value result) throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.DATE);
      return;
    }
    date(value.toLocalDate(), result);
  }

  static void date(LocalDate value, Value result) throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.DATE);
      return;
    }
    long epochDay = value.toEpochDay();
    if (!LocalTemporal.validDate(epochDay)) throw overflow();
    result.set(SqlTypeDescriptor.DATE, epochDay);
  }

  static void time(Time value, Value result) throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.time(6));
      return;
    }
    time(value.toLocalTime(), result);
  }

  static void time(LocalTime value, Value result) throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.time(6));
      return;
    }
    int precision = precision(value.getNano());
    long microseconds = value.toNanoOfDay() / 1_000L;
    if (!LocalTemporal.validTime(microseconds, precision)) throw overflow();
    result.set(SqlTypeDescriptor.time(precision), microseconds);
  }

  static void timestamp(Timestamp value, Value result) throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.timestamp(6));
      return;
    }
    timestamp(value.toLocalDateTime(), result);
  }

  static void timestamp(LocalDateTime value, Value result) throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.timestamp(6));
      return;
    }
    int precision = precision(value.getNano());
    long microseconds = localMicroseconds(value.toLocalDate(), value.toLocalTime());
    if (!LocalTemporal.validTimestamp(microseconds, precision)) throw overflow();
    result.set(SqlTypeDescriptor.timestamp(precision), microseconds);
  }

  static void timestampWithZone(OffsetDateTime value, Value result)
      throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.timestampWithTimeZone(6));
      return;
    }
    int offsetSeconds = value.getOffset().getTotalSeconds();
    if (offsetSeconds % 60 != 0
        || Math.abs(offsetSeconds / 60) > LocalTemporal.MAXIMUM_OFFSET_MINUTES) {
      throw JdbcExceptions.failure(
          StatusCode.INVALID_TIME_ZONE_DISPLACEMENT,
          "set TIMESTAMP WITH TIME ZONE parameter");
    }
    instant(value.toInstant(), result);
  }

  static void instant(Instant value, Value result) throws SQLException {
    if (value == null) {
      result.setNull(SqlTypeDescriptor.timestampWithTimeZone(6));
      return;
    }
    int precision = precision(value.getNano());
    long microseconds;
    try {
      microseconds = Math.addExact(
          Math.multiplyExact(
              value.getEpochSecond(), LocalTemporal.MICROSECONDS_PER_SECOND),
          value.getNano() / 1_000L);
    } catch (ArithmeticException failure) {
      throw overflow();
    }
    if (!LocalTemporal.validInstant(microseconds, precision)) throw overflow();
    result.set(SqlTypeDescriptor.timestampWithTimeZone(precision), microseconds);
  }

  private static long localMicroseconds(LocalDate date, LocalTime time)
      throws SQLException {
    long day = date.toEpochDay();
    if (!LocalTemporal.validDate(day)) throw overflow();
    return day * LocalTemporal.MICROSECONDS_PER_DAY + time.toNanoOfDay() / 1_000L;
  }

  private static int precision(int nanoseconds) throws SQLException {
    if (nanoseconds % 1_000 != 0) throw overflow();
    int microseconds = nanoseconds / 1_000;
    int precision = 6;
    while (precision > 0 && microseconds % 10 == 0) {
      precision--;
      microseconds /= 10;
    }
    return precision;
  }

  private static SQLException overflow() {
    return JdbcExceptions.failure(
        StatusCode.DATETIME_FIELD_OVERFLOW, "set temporal parameter");
  }

  static final class Value {
    private int descriptor;
    private long value;
    private boolean nullValue;

    int descriptor() { return descriptor; }
    long value() { return value; }
    boolean isNull() { return nullValue; }

    private void set(int typeDescriptor, long rawValue) {
      descriptor = typeDescriptor;
      value = rawValue;
      nullValue = false;
    }

    private void setNull(int typeDescriptor) {
      descriptor = typeDescriptor;
      value = 0;
      nullValue = true;
    }
  }
}
