package io.riverdb.jdbc;

import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.sql.SQLException;
import java.sql.Types;

/** Stateless JDBC marker and declared-null type policy. */
final class RiverJdbcParameterTypes {
  private RiverJdbcParameterTypes() {
  }

  static int countMarkers(String sql) {
    int count = 0;
    boolean quoted = false;
    for (int index = 0; index < sql.length(); index++) {
      char character = sql.charAt(index);
      if (character == '\'') {
        if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
          index++;
        } else {
          quoted = !quoted;
        }
      } else if (!quoted && character == '?') {
        count++;
      }
    }
    return count;
  }

  static int nullDescriptor(int sqlType) throws SQLException {
    return switch (sqlType) {
      case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT ->
          SqlTypeDescriptor.BIGINT;
      case Types.BOOLEAN, Types.BIT -> SqlTypeDescriptor.BOOLEAN;
      case Types.DECIMAL, Types.NUMERIC -> SqlTypeDescriptor.decimal(
          SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION, 0);
      case Types.VARCHAR, Types.CHAR ->
          SqlTypeDescriptor.varchar(Utf8Text.MAXIMUM_SCALARS);
      case Types.DATE -> SqlTypeDescriptor.DATE;
      case Types.TIME -> SqlTypeDescriptor.time(6);
      case Types.TIMESTAMP -> SqlTypeDescriptor.timestamp(6);
      case Types.TIMESTAMP_WITH_TIMEZONE ->
          SqlTypeDescriptor.timestampWithTimeZone(6);
      case Types.NULL -> 0;
      default -> throw JdbcExceptions.unsupported();
    };
  }
}
