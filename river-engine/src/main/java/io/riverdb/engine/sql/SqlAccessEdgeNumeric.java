package io.riverdb.engine.sql;

import io.riverdb.sql.SqlComparison;

/** Dispatches access normalization to range and scalar conversion policies. */
final class SqlAccessEdgeNumeric {
  private SqlAccessEdgeNumeric() { }

  static boolean range(SqlAccessEdgeSelector target, int column, int descriptor) {
    return SqlAccessEdgeRange.range(target, column, descriptor);
  }

  static boolean normalizeRange(SqlAccessEdgeSelector target, long lowerValue, int lowerDescriptor,
      SqlComparison lowerComparison, long upperValue, int upperDescriptor,
      SqlComparison upperComparison, int descriptor) {
    return SqlAccessEdgeRange.normalize(target, lowerValue, lowerDescriptor, lowerComparison,
        upperValue, upperDescriptor, upperComparison, descriptor);
  }

  static boolean convert(SqlAccessEdgeSelector target, long value, int source, int descriptor,
      SqlComparison comparison) {
    return SqlAccessEdgeConversion.convert(target, value, source, descriptor, comparison);
  }
}
