package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.sql.SqlComparison;

/** Normalizes lower and upper access bounds. */
final class SqlAccessEdgeRange {
  private SqlAccessEdgeRange() { }

  static boolean range(SqlAccessEdgeSelector target, int column, int descriptor) {
    long lower = SqlValueDomain.minimumFixed(descriptor), upper = SqlValueDomain.exclusiveMaximumFixed(descriptor);
    boolean hasLower = false, hasUpper = upper != Long.MIN_VALUE, sawBound = false;
    for (int edge = 0; edge < target.count; edge++) {
      if (target.columns[edge] != column) continue;
      SqlComparison comparison = target.comparisons[edge];
      if (comparison == SqlComparison.HALF_OPEN_RANGE) {
        if (!SqlAccessEdgeRangeConversion.convert(
            target, target.values[edge], target.descriptors[edge], descriptor,
            SqlComparison.GREATER_OR_EQUAL)) continue;
        lower = hasLower ? Math.max(lower, target.convertedValue) : target.convertedValue; hasLower = true; sawBound = true;
        if (!SqlAccessEdgeRangeConversion.convert(
            target, target.upperValues[edge], target.upperDescriptors[edge], descriptor,
            SqlComparison.LESS_OR_EQUAL)) continue;
        upper = hasUpper ? Math.min(upper, target.convertedValue) : target.convertedValue; hasUpper = true; sawBound = true;
      } else if (SqlAccessEdgeSelector.lower(comparison)
          && SqlAccessEdgeRangeConversion.convert(
              target, target.values[edge], target.descriptors[edge], descriptor, comparison)) {
        lower = hasLower ? Math.max(lower, target.convertedValue) : target.convertedValue; hasLower = true; sawBound = true;
      } else if (SqlAccessEdgeSelector.upper(comparison)
          && SqlAccessEdgeRangeConversion.convert(
              target, target.values[edge], target.descriptors[edge], descriptor, comparison)) {
        upper = hasUpper ? Math.min(upper, target.convertedValue) : target.convertedValue; hasUpper = true; sawBound = true;
      }
    }
    if (!sawBound) return false;
    if (!hasLower) lower = SqlValueDomain.minimumFixed(descriptor);
    if (!hasUpper) { upper = SqlValueDomain.exclusiveMaximumFixed(descriptor); if (upper == Long.MIN_VALUE) return false; }
    target.normalizedLower = lower; target.normalizedUpper = upper;
    return lower < upper;
  }

  static boolean normalize(SqlAccessEdgeSelector target, long lowerValue, int lowerDescriptor,
      SqlComparison lowerComparison, long upperValue, int upperDescriptor,
      SqlComparison upperComparison, int descriptor) {
    if (!SqlAccessEdgeRangeConversion.convert(
        target, lowerValue, lowerDescriptor, descriptor, lowerComparison)) return false;
    target.normalizedLower = target.convertedValue;
    if (!SqlAccessEdgeRangeConversion.convert(
        target, upperValue, upperDescriptor, descriptor, upperComparison)) return false;
    target.normalizedUpper = target.convertedValue;
    return target.normalizedLower < target.normalizedUpper;
  }

}
