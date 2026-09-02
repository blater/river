package io.riverdb.sql;

import java.util.Arrays;

final class SqlAggregateCapacity {
  private SqlAggregateCapacity() { }

  static boolean ensure(SqlAggregateSet set, int invocations, int outputs) {
    try {
      int invocationCapacity = grow(set.kinds.length, invocations);
      int outputCapacity = grow(set.outputInvocations.length, outputs);
      int[] kinds = invocationCapacity == set.kinds.length
          ? set.kinds : Arrays.copyOf(set.kinds, invocationCapacity);
      int[] operands = invocationCapacity == set.operandProjections.length
          ? set.operandProjections
          : Arrays.copyOf(set.operandProjections, invocationCapacity);
      int[] output = outputCapacity == set.outputInvocations.length
          ? set.outputInvocations
          : Arrays.copyOf(set.outputInvocations, outputCapacity);
      set.kinds = kinds;
      set.operandProjections = operands;
      set.outputInvocations = output;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private static int grow(int current, int required) {
    int capacity = current;
    while (capacity < required) capacity = Math.min(
        SqlAggregateSet.MAXIMUM_INVOCATIONS, capacity * 2);
    return capacity;
  }
}
