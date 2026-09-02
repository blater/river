package io.riverdb.sql;

import java.util.Arrays;

/** Atomic geometric growth for mutation expression programs and nodes. */
final class SqlMutationCapacity {
  private SqlMutationCapacity() { }

  static boolean ensure(SqlMutationExpressions value, int programs, int nodes) {
    try {
      int programCapacity = grow(value.counts.length, programs);
      int nodeCapacity = grow(value.operators.length, nodes);
      int[] offsets = programCapacity == value.offsets.length
          ? value.offsets : Arrays.copyOf(value.offsets, programCapacity);
      int[] counts = programCapacity == value.counts.length
          ? value.counts : Arrays.copyOf(value.counts, programCapacity);
      byte[] operators = nodeCapacity == value.operators.length
          ? value.operators : Arrays.copyOf(value.operators, nodeCapacity);
      long[] operandHighs = nodeCapacity == value.operandHighs.length
          ? value.operandHighs : Arrays.copyOf(value.operandHighs, nodeCapacity);
      long[] operands = nodeCapacity == value.operands.length
          ? value.operands : Arrays.copyOf(value.operands, nodeCapacity);
      int[] descriptors = nodeCapacity == value.descriptors.length
          ? value.descriptors : Arrays.copyOf(value.descriptors, nodeCapacity);
      value.offsets = offsets;
      value.counts = counts;
      value.operators = operators;
      value.operandHighs = operandHighs;
      value.operands = operands;
      value.descriptors = descriptors;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private static int grow(int current, int required) {
    int capacity = current;
    while (capacity < required) capacity = Math.min(
        SqlScalarExpression.MAXIMUM_NODES, capacity * 2);
    return capacity;
  }
}
