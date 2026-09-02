package io.riverdb.jdbc;

import io.riverdb.base.type.SqlTypeDescriptor;
import java.sql.Types;

/** Canonical JDBC metadata mapping for River type descriptors. */
final class RiverJdbcTypeMetadata {
  private RiverJdbcTypeMetadata() {
  }

  static int jdbcType(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> Types.SMALLINT;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> Types.INTEGER;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> Types.BIGINT;
      case SqlTypeDescriptor.TYPE_ID_VARCHAR -> Types.VARCHAR;
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> Types.BOOLEAN;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> Types.DECIMAL;
      case SqlTypeDescriptor.TYPE_ID_REAL -> Types.REAL;
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> Types.DOUBLE;
      case SqlTypeDescriptor.TYPE_ID_DATE -> Types.DATE;
      case SqlTypeDescriptor.TYPE_ID_TIME -> Types.TIME;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> Types.TIMESTAMP;
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          Types.TIMESTAMP_WITH_TIMEZONE;
      default -> Types.OTHER;
    };
  }

  static String typeName(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> "SMALLINT";
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> "INTEGER";
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> "BIGINT";
      case SqlTypeDescriptor.TYPE_ID_VARCHAR -> "VARCHAR";
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> "BOOLEAN";
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> "DECIMAL";
      case SqlTypeDescriptor.TYPE_ID_REAL -> "REAL";
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> "DOUBLE PRECISION";
      case SqlTypeDescriptor.TYPE_ID_DATE -> "DATE";
      case SqlTypeDescriptor.TYPE_ID_TIME -> "TIME";
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> "TIMESTAMP";
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          "TIMESTAMP WITH TIME ZONE";
      default -> "OTHER";
    };
  }

  static int precision(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> 5;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> 10;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> 19;
      case SqlTypeDescriptor.TYPE_ID_VARCHAR, SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          SqlTypeDescriptor.parameterOne(descriptor);
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> 1;
      case SqlTypeDescriptor.TYPE_ID_REAL -> 7;
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> 15;
      case SqlTypeDescriptor.TYPE_ID_DATE -> 10;
      case SqlTypeDescriptor.TYPE_ID_TIME ->
          8 + fractionalSuffix(SqlTypeDescriptor.parameterOne(descriptor));
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP ->
          19 + fractionalSuffix(SqlTypeDescriptor.parameterOne(descriptor));
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          25 + fractionalSuffix(SqlTypeDescriptor.parameterOne(descriptor));
      default -> 0;
    };
  }

  static int displaySize(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> 6;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> 11;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> 20;
      case SqlTypeDescriptor.TYPE_ID_VARCHAR -> SqlTypeDescriptor.parameterOne(descriptor);
      case SqlTypeDescriptor.TYPE_ID_BOOLEAN -> 5;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          SqlTypeDescriptor.parameterOne(descriptor)
              + (SqlTypeDescriptor.parameterTwo(descriptor) > 0 ? 2 : 1);
      case SqlTypeDescriptor.TYPE_ID_REAL -> 15;
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> 24;
      default -> precision(descriptor);
    };
  }

  static boolean numeric(int type) {
    return type == Types.BIGINT
        || type == Types.DECIMAL
        || type == Types.INTEGER
        || type == Types.SMALLINT
        || type == Types.REAL
        || type == Types.FLOAT
        || type == Types.DOUBLE;
  }

  private static int fractionalSuffix(int precision) {
    return precision == 0 ? 0 : precision + 1;
  }
}
