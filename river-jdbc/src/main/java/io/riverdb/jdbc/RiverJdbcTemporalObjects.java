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

/** Converts temporal primitives to JDBC and java.time object types. */
final class RiverJdbcTemporalObjects {
  private RiverJdbcTemporalObjects() { }

  static Object object(long value, int descriptor) throws SQLException {
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> dateValue(value);
      case SqlTypeDescriptor.TYPE_ID_TIME -> timeValue(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> timestampValue(value, precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          OffsetDateTime.ofInstant(instant(value, precision), ZoneOffset.UTC);
      default -> throw JdbcExceptions.unsupported();
    };
  }

  static Object convert(long value, int descriptor, Class<?> target, char[] characters)
      throws SQLException {
    return RiverJdbcTemporalTargetConversion.convert(value, descriptor, target, characters);
  }

  static boolean supports(int descriptor, Class<?> target) {
    return RiverJdbcTemporalTargetConversion.supports(descriptor, target);
  }

  static Date date(long value, int descriptor) throws SQLException {
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_DATE) throw JdbcExceptions.unsupported();
    return Date.valueOf(dateValue(value));
  }

  static Time time(long value, int descriptor) throws SQLException {
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_TIME) throw JdbcExceptions.unsupported();
    return Time.valueOf(timeValue(value, SqlTypeDescriptor.parameterOne(descriptor)));
  }

  static Timestamp timestamp(long value, int descriptor) throws SQLException {
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP) return Timestamp.valueOf(timestampValue(value, SqlTypeDescriptor.parameterOne(descriptor)));
    if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) return Timestamp.from(instant(value, SqlTypeDescriptor.parameterOne(descriptor)));
    throw JdbcExceptions.unsupported();
  }

  private static LocalDate dateValue(long value) throws SQLException {
    if (!LocalTemporal.validDate(value)) throw invalid();
    return LocalDate.ofEpochDay(value);
  }

  private static LocalTime timeValue(long value, int precision) throws SQLException {
    if (!LocalTemporal.validTime(value, precision)) throw invalid();
    return LocalTime.ofNanoOfDay(value * 1_000L);
  }

  private static LocalDateTime timestampValue(long value, int precision) throws SQLException {
    if (!LocalTemporal.validTimestamp(value, precision)) throw invalid();
    return LocalDateTime.ofEpochSecond(Math.floorDiv(value, LocalTemporal.MICROSECONDS_PER_SECOND), nanos(value), ZoneOffset.UTC);
  }

  private static Instant instant(long value, int precision) throws SQLException {
    if (!LocalTemporal.validInstant(value, precision)) throw invalid();
    return Instant.ofEpochSecond(Math.floorDiv(value, LocalTemporal.MICROSECONDS_PER_SECOND), nanos(value));
  }

  private static int nanos(long value) {
    return (int) Math.floorMod(value, LocalTemporal.MICROSECONDS_PER_SECOND) * 1_000;
  }

  private static SQLException invalid() {
    return JdbcExceptions.invalid("temporal value is outside its declared domain");
  }
}
