package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;

/** Fixed-width assignment compatibility shared by INSERT and UPDATE. */
final class SqlMutationAssignmentTypes {
  private SqlMutationAssignmentTypes() {}

  static boolean compatible(
      int source, int target, boolean nullValue, boolean defaultValue) {
    if (nullValue || defaultValue
        || SqlTypeDescriptor.canImplicitlyCast(source, target)) {
      return true;
    }
    return SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && exactNumeric(source)
        && scale(source) <= SqlTypeDescriptor.parameterTwo(target);
  }

  private static boolean exactNumeric(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_BIGINT
        || type == SqlTypeDescriptor.TYPE_ID_DECIMAL;
  }

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
}
