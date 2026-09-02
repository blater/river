package io.riverdb.sql;

import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Field extraction and temporal arithmetic descriptor rules. */
final class SqlTemporalFieldRules {
  private SqlTemporalFieldRules() {
  }

  static int extractField(SqlParserInput input, CharSequence sql) {
    if (input.consumeKeyword(sql, "YEAR")) return LocalTemporal.EXTRACT_YEAR;
    if (input.consumeKeyword(sql, "MONTH")) return LocalTemporal.EXTRACT_MONTH;
    if (input.consumeKeyword(sql, "DAY")) return LocalTemporal.EXTRACT_DAY;
    if (input.consumeKeyword(sql, "HOUR")) return LocalTemporal.EXTRACT_HOUR;
    if (input.consumeKeyword(sql, "MINUTE")) return LocalTemporal.EXTRACT_MINUTE;
    if (input.consumeKeyword(sql, "SECOND")) return LocalTemporal.EXTRACT_SECOND;
    if (input.consumeKeyword(sql, "TIMEZONE_HOUR")) {
      return LocalTemporal.EXTRACT_TIMEZONE_HOUR;
    }
    return input.consumeKeyword(sql, "TIMEZONE_MINUTE")
        ? LocalTemporal.EXTRACT_TIMEZONE_MINUTE : 0;
  }

  static int extractDescriptor(int source, int field) {
    int type = SqlTypeDescriptor.typeId(source);
    if (field >= LocalTemporal.EXTRACT_YEAR && field <= LocalTemporal.EXTRACT_DAY) {
      return dateBearing(type) ? SqlTypeDescriptor.BIGINT : 0;
    }
    if (field >= LocalTemporal.EXTRACT_HOUR && field <= LocalTemporal.EXTRACT_SECOND) {
      if (!timeBearing(type)) return 0;
      int precision = SqlTypeDescriptor.parameterOne(source);
      return field == LocalTemporal.EXTRACT_SECOND
          ? SqlTypeDescriptor.decimal(2 + precision, precision)
          : SqlTypeDescriptor.BIGINT;
    }
    return field >= LocalTemporal.EXTRACT_TIMEZONE_HOUR
            && field <= LocalTemporal.EXTRACT_TIMEZONE_MINUTE
            && type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? SqlTypeDescriptor.BIGINT : 0;
  }

  static int additiveDescriptor(int operator, int left, int right) {
    int leftType = SqlTypeDescriptor.typeId(left);
    int rightType = SqlTypeDescriptor.typeId(right);
    if (leftType != SqlTypeDescriptor.TYPE_ID_DATE) {
      return SqlNumericExpressionTypes.binary(operator, left, right);
    }
    if (SqlNumericTypeRules.isIntegral(right)) return SqlTypeDescriptor.DATE;
    return operator == SqlScalarExpression.SUBTRACT
            && rightType == SqlTypeDescriptor.TYPE_ID_DATE
        ? SqlTypeDescriptor.BIGINT : 0;
  }

  private static boolean dateBearing(int type) {
    return type == SqlTypeDescriptor.TYPE_ID_DATE
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }

  private static boolean timeBearing(int type) {
    return type == SqlTypeDescriptor.TYPE_ID_TIME
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }
}
