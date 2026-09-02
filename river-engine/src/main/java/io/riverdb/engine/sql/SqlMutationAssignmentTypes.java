package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
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
    return SqlNumericTypeRules.canAssign(source, target);
  }
}
