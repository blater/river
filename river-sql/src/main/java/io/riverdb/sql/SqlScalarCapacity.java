package io.riverdb.sql;

import java.util.Arrays;

final class SqlScalarCapacity {
  private SqlScalarCapacity() { }

  static boolean ensure(SqlScalarExpression expression, int required) {
    if (required <= expression.operators.length) return true;
    int capacity = expression.operators.length;
    while (capacity < required) capacity = Math.min(SqlScalarExpression.MAXIMUM_NODES,
        capacity * 2);
    try {
      byte[] operators = Arrays.copyOf(expression.operators, capacity);
      long[] operandHighs = Arrays.copyOf(expression.operandHighs, capacity);
      long[] operands = Arrays.copyOf(expression.operands, capacity);
      int[] descriptors = Arrays.copyOf(expression.typeDescriptors, capacity);
      expression.operators = operators;
      expression.operandHighs = operandHighs;
      expression.operands = operands;
      expression.typeDescriptors = descriptors;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }
}
