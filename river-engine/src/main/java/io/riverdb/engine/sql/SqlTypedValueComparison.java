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
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      default -> false;
    };
  }
}
