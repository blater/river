package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Explicit-cast and timezone descriptor rules for row expressions. */
final class SqlRowExpressionCasts {
  private SqlRowExpressionCasts() {
  }

  static boolean admitted(int source, int target) {
    if (!SqlTypeDescriptor.canExplicitlyCast(source, target)) return false;
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    return temporalPair(sourceType, targetType)
        || sourceType == SqlTypeDescriptor.TYPE_ID_VARCHAR
            && SqlRowExpressionTypes.temporal(targetType)
        || targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR
            && SqlRowExpressionTypes.temporal(sourceType);
  }

  static int atTimeZone(int source) {
    int type = SqlTypeDescriptor.typeId(source);
    return type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        ? SqlTypeDescriptor.timestampWithTimeZone(SqlTypeDescriptor.parameterOne(source))
        : type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            ? SqlTypeDescriptor.timestamp(SqlTypeDescriptor.parameterOne(source)) : 0;
  }

  private static boolean temporalPair(int source, int target) {
    return source == target && SqlRowExpressionTypes.temporal(source)
        || source == SqlTypeDescriptor.TYPE_ID_DATE
            && target == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || source == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            && (target == SqlTypeDescriptor.TYPE_ID_DATE
                || target == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE)
        || source == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            && target == SqlTypeDescriptor.TYPE_ID_TIMESTAMP;
  }
}
