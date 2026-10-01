package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.sql.SqlComparison;

/** Allocation-free comparison for two already-validated fixed SQL values. */
final class SqlTypedValueComparison {
  private SqlTypedValueComparison() { }

  static boolean matches(
      long left, int leftDescriptor, long right, int rightDescriptor,
      SqlComparison comparison) {
    int compared = SqlNumericTypeRules.isNumeric(leftDescriptor)
            && SqlNumericTypeRules.isNumeric(rightDescriptor)
        ? SqlNumericValue.compare(left, leftDescriptor, right, rightDescriptor)
        : Long.compare(left, right);
    return SqlDescriptorComparison.matches(compared, comparison);
  }
}
