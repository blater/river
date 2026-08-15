package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Type policy for deterministic persisted CHECK casts. */
final class SqlCheckExpressionTypes {
  static final int UNSUPPORTED = -1;

  private SqlCheckExpressionTypes() {
  }

  static int castDescriptor(int source, int target) {
    if (!SqlTypeDescriptor.canExplicitlyCast(source, target)) return 0;
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (sourceType == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR
        || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        || sourceType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
            && targetType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP) {
      return UNSUPPORTED;
    }
    return deterministic(sourceType, targetType) ? target : UNSUPPORTED;
  }

  private static boolean deterministic(int source, int target) {
    return source == target
            && source >= SqlTypeDescriptor.TYPE_ID_DATE
            && source <= SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE
        || source == SqlTypeDescriptor.TYPE_ID_DATE
            && target == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || source == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
            && target == SqlTypeDescriptor.TYPE_ID_DATE;
  }
}
