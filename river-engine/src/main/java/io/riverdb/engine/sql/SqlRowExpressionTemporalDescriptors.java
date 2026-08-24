package io.riverdb.engine.sql;

import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** EXTRACT result descriptors for row expressions. */
final class SqlRowExpressionTemporalDescriptors {
  private SqlRowExpressionTemporalDescriptors() {
  }

  static int extract(int source, int field) {
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
