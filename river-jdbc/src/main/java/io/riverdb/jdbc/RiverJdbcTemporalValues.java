package io.riverdb.jdbc;

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
import java.time.ZoneOffset;

/** Converts validated primitive River temporal values at the JDBC boundary. */
final class RiverJdbcTemporalValues {
  private RiverJdbcTemporalValues() {
  }

  static boolean isTemporal(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_DATE
        || type == SqlTypeDescriptor.TYPE_ID_TIME
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  static String string(
      long value, int descriptor, char[] characters) throws SQLException {
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    int length = switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DATE ->
          LocalTemporal.formatDate(value, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          LocalTemporal.formatTime(value, precision, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          LocalTemporal.formatTimestamp(value, precision, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          LocalTemporal.formatTimestampWithOffset(
              value, precision, 0, characters, 0);
      default -> -1;
    };
    if (length < 0) throw invalid();
    return new String(characters, 0, length);
  }

  static Object object(long value, int descriptor) throws SQLException {
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> localDate(value);
      case SqlTypeDescriptor.TYPE_ID_TIME -> localTime(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          localTimestamp(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          OffsetDateTime.ofInstant(instant(value, precision), ZoneOffset.UTC);
      default -> throw JdbcExceptions.unsupported();
    };
  }

  static Object convert(
      long value,
      int descriptor,
      Class<?> target,
      char[] characters) throws SQLException {
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (target == String.class) return string(value, descriptor, characters);
    if (type == SqlTypeDescriptor.TYPE_ID_DATE) {
      LocalDate date = localDate(value);
      if (target == LocalDate.class) return date;
      if (target == Date.class) return Date.valueOf(date);
    } else if (type == SqlTypeDescriptor.TYPE_ID_TIME) {
      LocalTime time = localTime(
          value, SqlTypeDescriptor.parameterOne(descriptor));
      if (target == LocalTime.class) return time;
      if (target == Time.class) return Time.valueOf(time);
    } else if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP) {
      LocalDateTime timestamp = localTimestamp(
          value, SqlTypeDescriptor.parameterOne(descriptor));
      if (target == LocalDateTime.class) return timestamp;
      if (target == Timestamp.class) return Timestamp.valueOf(timestamp);
    } else if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) {
      Instant instant = instant(
          value, SqlTypeDescriptor.parameterOne(descriptor));
      if (target == OffsetDateTime.class) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
      }
      if (target == Timestamp.class) return Timestamp.from(instant);
    }
    throw JdbcExceptions.unsupported();
  }

  static boolean supportsObjectClass(int descriptor, Class<?> target) {
    if (target == String.class) return true;
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DATE ->
          target == LocalDate.class || target == Date.class;
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          target == LocalTime.class || target == Time.class;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          target == LocalDateTime.class || target == Timestamp.class;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          target == OffsetDateTime.class || target == Timestamp.class;
      default -> false;
    };
  }

  static Date date(long value, int descriptor) throws SQLException {
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_DATE) {
      throw JdbcExceptions.unsupported();
    }
    return Date.valueOf(localDate(value));
  }

  static Time time(long value, int descriptor) throws SQLException {
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_TIME) {
      throw JdbcExceptions.unsupported();
    }
    return Time.valueOf(localTime(
        value, SqlTypeDescriptor.parameterOne(descriptor)));
  }

  static Timestamp timestamp(long value, int descriptor) throws SQLException {
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP) {
      return Timestamp.valueOf(localTimestamp(
          value, SqlTypeDescriptor.parameterOne(descriptor)));
    }
    if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) {
      return Timestamp.from(instant(
          value, SqlTypeDescriptor.parameterOne(descriptor)));
    }
    throw JdbcExceptions.unsupported();
  }

  private static LocalDate localDate(long epochDay) throws SQLException {
    if (!LocalTemporal.validDate(epochDay)) throw invalid();
    return LocalDate.ofEpochDay(epochDay);
  }

  private static LocalTime localTime(long microseconds, int precision)
      throws SQLException {
    if (!LocalTemporal.validTime(microseconds, precision)) throw invalid();
    return LocalTime.ofNanoOfDay(microseconds * 1_000L);
  }

  private static LocalDateTime localTimestamp(long microseconds, int precision)
      throws SQLException {
    if (!LocalTemporal.validTimestamp(microseconds, precision)) throw invalid();
    return LocalDateTime.ofEpochSecond(
        Math.floorDiv(microseconds, LocalTemporal.MICROSECONDS_PER_SECOND),
        nanos(microseconds),
        ZoneOffset.UTC);
  }

  private static Instant instant(long microseconds, int precision)
      throws SQLException {
    if (!LocalTemporal.validInstant(microseconds, precision)) throw invalid();
    return Instant.ofEpochSecond(
        Math.floorDiv(microseconds, LocalTemporal.MICROSECONDS_PER_SECOND),
        nanos(microseconds));
  }

  private static int nanos(long microseconds) {
    return (int) Math.floorMod(
        microseconds, LocalTemporal.MICROSECONDS_PER_SECOND) * 1_000;
  }

  private static SQLException invalid() {
    return JdbcExceptions.invalid("temporal value is outside its declared domain");
  }
}
