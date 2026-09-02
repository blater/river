package io.riverdb.sql;

import java.util.Arrays;

/** Geometric parse-time storage for scalar descriptor inference. */
final class SqlScalarParserCapacity {
  private SqlScalarParserCapacity() {
  }

  static boolean ensure(SqlScalarExpressionParser parser, int required) {
    if (required <= parser.descriptorStack.length) return true;
    if (required > SqlScalarExpression.MAXIMUM_NODES) return false;
    int capacity = parser.descriptorStack.length;
    while (capacity < required) {
      capacity = Math.min(SqlScalarExpression.MAXIMUM_NODES, capacity * 2);
    }
    try {
      parser.descriptorStack = Arrays.copyOf(parser.descriptorStack, capacity);
      return true;
    } catch (OutOfMemoryError exhausted) {
      return false;
    }
  }
}
