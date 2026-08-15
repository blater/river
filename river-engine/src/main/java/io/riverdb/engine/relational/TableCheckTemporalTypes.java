package io.riverdb.engine.relational;

import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Descriptor rules for context-free temporal CHECK operators. */
final class TableCheckTemporalTypes {
  private TableCheckTemporalTypes() {
  }

  static int castDescriptor(int source, int target) {
    if (!SqlTypeDescriptor.canExplicitlyCast(source, target)) return 0;
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    boolean sameTemporal = sourceType == targetType && temporal(sourceType);
    boolean dateTimestamp = sourceType == SqlTypeDescriptor.TYPE_ID_DATE
            && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            && targetType == SqlTypeDescriptor.TYPE_ID_DATE;
    return sameTemporal || dateTimestamp ? target : 0;
  }

  static int extractDescriptor(int source, long fieldValue) {
    if (fieldValue < Integer.MIN_VALUE || fieldValue > Integer.MAX_VALUE) return 0;
    int field = (int) fieldValue;
    int type = SqlTypeDescriptor.typeId(source);
    if (field >= LocalTemporal.EXTRACT_YEAR && field <= LocalTemporal.EXTRACT_DAY) {
      return type == SqlTypeDescriptor.TYPE_ID_DATE
              || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
              || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
          ? SqlTypeDescriptor.BIGINT : 0;
    }
    if (field >= LocalTemporal.EXTRACT_HOUR && field <= LocalTemporal.EXTRACT_SECOND) {
      if (type != SqlTypeDescriptor.TYPE_ID_TIME
          && type != SqlTypeDescriptor.TYPE_ID_TIMESTAMP
          && type != SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) return 0;
      int precision = SqlTypeDescriptor.parameterOne(source);
      return field == LocalTemporal.EXTRACT_SECOND
          ? SqlTypeDescriptor.decimal(2 + precision, precision)
          : SqlTypeDescriptor.BIGINT;
    }
    return (field == LocalTemporal.EXTRACT_TIMEZONE_HOUR
            || field == LocalTemporal.EXTRACT_TIMEZONE_MINUTE)
            && type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        ? SqlTypeDescriptor.BIGINT : 0;
  }

  private static boolean temporal(int type) {
    return type >= SqlTypeDescriptor.TYPE_ID_DATE
        && type <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }
}
