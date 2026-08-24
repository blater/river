package io.riverdb.jdbc;

import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.sql.SQLException;

/** Formats validated temporal primitives as JDBC text. */
final class RiverJdbcTemporalConversion {
  private RiverJdbcTemporalConversion() { }

  static String string(long value, int descriptor, char[] characters) throws SQLException {
    int precision = SqlTypeDescriptor.parameterOne(descriptor);
    int length = switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DATE -> LocalTemporal.formatDate(value, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIME -> LocalTemporal.formatTime(value, precision, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> LocalTemporal.formatTimestamp(value, precision, characters, 0);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE -> LocalTemporal.formatTimestampWithOffset(value, precision, 0, characters, 0);
      default -> -1;
    };
    if (length < 0) throw JdbcExceptions.invalid("temporal value is outside its declared domain");
    return new String(characters, 0, length);
  }

  static Object object(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalObjects.object(value, descriptor);
  }

  static Object convert(long value, int descriptor, Class<?> target, char[] characters) throws SQLException {
    return RiverJdbcTemporalObjects.convert(value, descriptor, target, characters);
  }

  static boolean supportsObjectClass(int descriptor, Class<?> target) {
    return RiverJdbcTemporalObjects.supports(descriptor, target);
  }

  static java.sql.Date date(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalObjects.date(value, descriptor);
  }

  static java.sql.Time time(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalObjects.time(value, descriptor);
  }

  static java.sql.Timestamp timestamp(long value, int descriptor) throws SQLException {
    return RiverJdbcTemporalObjects.timestamp(value, descriptor);
  }
}
