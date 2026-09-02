package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlComparison;

/** Converts one access literal into a target fixed descriptor. */
final class SqlAccessEdgeConversion {
  private SqlAccessEdgeConversion() { }

  static boolean convert(SqlAccessEdgeSelector target, long value, int source, int descriptor,
      SqlComparison comparison) {
    if (source == descriptor) {
      target.convertedValue = value;
      return true;
    }
    if (!numeric(source) || !numeric(descriptor)) {
      if (!SqlTypeDescriptor.canImplicitlyCast(source, descriptor)) return false;
      target.convertedValue = value;
      return true;
    }
    if (comparison != SqlComparison.EQUAL) return false;
    io.riverdb.base.error.StatusCode status = SqlNumericValue.assign(
        value, source, descriptor, target.decimal, target.wide);
    if (!status.isOk()
        || SqlNumericValue.compare(
            value, source, target.decimal.value, descriptor) != 0) return false;
    target.convertedValue = target.decimal.value;
    return true;
  }

  private static boolean numeric(int descriptor) {
    return SqlNumericTypeRules.isNumeric(descriptor);
  }
}
