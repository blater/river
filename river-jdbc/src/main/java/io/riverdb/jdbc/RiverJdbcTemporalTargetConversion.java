package io.riverdb.jdbc;

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

/** Applies requested JDBC target conversions for temporal values. */
final class RiverJdbcTemporalTargetConversion {
  private RiverJdbcTemporalTargetConversion() { }

  static Object convert(long value, int descriptor, Class<?> target, char[] characters)
      throws SQLException {
    int type = SqlTypeDescriptor.typeId(descriptor);
    if (target == String.class) return RiverJdbcTemporalConversion.string(value, descriptor, characters);
    if (type == SqlTypeDescriptor.TYPE_ID_DATE) {
      LocalDate date = LocalDate.ofEpochDay(value);
      if (target == LocalDate.class) return date;
      if (target == Date.class) return Date.valueOf(date);
    } else if (type == SqlTypeDescriptor.TYPE_ID_TIME) {
      LocalTime time = LocalTime.ofNanoOfDay(value * 1_000L);
      if (target == LocalTime.class) return time;
      if (target == Time.class) return Time.valueOf(time);
    } else if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP) {
      LocalDateTime timestamp = LocalDateTime.ofEpochSecond(
          Math.floorDiv(value, 1_000_000L), nanos(value), ZoneOffset.UTC);
      if (target == LocalDateTime.class) return timestamp;
      if (target == Timestamp.class) return Timestamp.valueOf(timestamp);
    } else if (type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) {
      Instant instant = Instant.ofEpochSecond(Math.floorDiv(value, 1_000_000L), nanos(value));
      if (target == OffsetDateTime.class) return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
      if (target == Timestamp.class) return Timestamp.from(instant);
    }
    throw JdbcExceptions.unsupported();
  }

  static boolean supports(int descriptor, Class<?> target) {
    if (target == String.class) return true;
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> target == LocalDate.class || target == Date.class;
      case SqlTypeDescriptor.TYPE_ID_TIME -> target == LocalTime.class || target == Time.class;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> target == LocalDateTime.class || target == Timestamp.class;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE -> target == OffsetDateTime.class || target == Timestamp.class;
      default -> false;
    };
  }

  private static int nanos(long value) {
    return (int) Math.floorMod(value, 1_000_000L) * 1_000;
  }
}
