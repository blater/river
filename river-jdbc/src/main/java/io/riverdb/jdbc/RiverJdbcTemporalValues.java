package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/** Converts validated primitive River temporal values at the JDBC boundary. */
final class RiverJdbcTemporalValues {
  private RiverJdbcTemporalValues() { }

  static boolean isTemporal(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type >= SqlTypeDescriptor.TYPE_ID_DATE
        && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  static String string(long value, int descriptor, char[] characters) throws SQLException {
    return RiverJdbcTemporalConversion.string(value, descriptor, characters);
  }

  static Object object(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalConversion.object(value, descriptor);
  }

  static Object convert(long value, int descriptor, Class<?> target, char[] characters)
      throws SQLException {
    return RiverJdbcTemporalConversion.convert(value, descriptor, target, characters);
  }

  static boolean supportsObjectClass(int descriptor, Class<?> target) {
    return RiverJdbcTemporalConversion.supportsObjectClass(descriptor, target);
  }

  static Date date(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalConversion.date(value, descriptor);
  }

  static Time time(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalConversion.time(value, descriptor);
  }

  static Timestamp timestamp(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalConversion.timestamp(value, descriptor);
  }
}
