package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.sql.SqlComparison;

/** Converts one predicate value into a safe legacy index-range boundary. */
final class SqlAccessEdgeRangeConversion {
  private SqlAccessEdgeRangeConversion() { }

  static boolean convert(
      SqlAccessEdgeSelector target,
      long value,
      int source,
      int descriptor,
      SqlComparison comparison) {
    boolean numeric = exact(source) && exact(descriptor);
    if (!compatible(source, descriptor, numeric)) return false;
    boolean exact = quantize(target, value, source, descriptor, numeric);
    if (!publish(target, value, source, descriptor, numeric)) return false;
    return shiftExclusive(target, descriptor, comparison, exact);
  }

  private static boolean compatible(int source, int descriptor, boolean exact) {
    if (exact) return true;
    if (source == descriptor) return !SqlNumericTypeRules.isApproximate(source);
    return !SqlNumericTypeRules.isNumeric(source)
        && !SqlNumericTypeRules.isNumeric(descriptor)
        && SqlTypeDescriptor.canImplicitlyCast(source, descriptor);
  }

  private static boolean quantize(
      SqlAccessEdgeSelector target,
      long value,
      int source,
      int descriptor,
      boolean numeric) {
    return !numeric || source == descriptor
        || ExactDecimal.quantize(
            value, source, descriptor, false, true, target.decimal, target.wide).isOk();
  }

  private static boolean publish(
      SqlAccessEdgeSelector target,
      long value,
      int source,
      int descriptor,
      boolean numeric) {
    if (!numeric || source == descriptor) {
      target.convertedValue = value;
      return true;
    }
    if (!ExactDecimal.ceilingScale(value, source, descriptor, target.decimal)) return false;
    target.convertedValue = target.decimal.value;
    return true;
  }

  private static boolean shiftExclusive(
      SqlAccessEdgeSelector target,
      int descriptor,
      SqlComparison comparison,
      boolean exact) {
    if (!exact || comparison != SqlComparison.GREATER_THAN
        && comparison != SqlComparison.LESS_OR_EQUAL) return true;
    if (target.convertedValue == Long.MAX_VALUE
        || !SqlValueDomain.validFixed(descriptor, target.convertedValue + 1)) return false;
    target.convertedValue++;
    return true;
  }

  private static boolean exact(int descriptor) {
    return SqlNumericTypeRules.isExact(descriptor);
  }
}
