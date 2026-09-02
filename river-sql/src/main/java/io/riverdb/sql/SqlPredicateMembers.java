package io.riverdb.sql;

import java.util.Arrays;

/** Atomic geometric storage for predicate membership literals. */
final class SqlPredicateMembers {
  private static final int INITIAL_CAPACITY = 16;

  private SqlPredicateMembers() {
  }

  static boolean ensure(SqlBooleanPredicateProgram value, int required) {
    if (required == 0
        || value.memberValues != null && required <= value.memberValues.length) return true;
    int capacity = value.memberValues == null ? INITIAL_CAPACITY : value.memberValues.length;
    while (capacity < required) capacity = Math.min(
        SqlBooleanPredicateProgram.MAXIMUM_MEMBERS, capacity * 2);
    try {
      long[] values = value.memberValues == null
          ? new long[capacity] : Arrays.copyOf(value.memberValues, capacity);
      long[] highs = value.memberHighs == null
          ? new long[capacity] : Arrays.copyOf(value.memberHighs, capacity);
      int[] descriptors = value.memberDescriptors == null
          ? new int[capacity] : Arrays.copyOf(value.memberDescriptors, capacity);
      boolean[] nulls = value.memberNulls == null
          ? new boolean[capacity] : Arrays.copyOf(value.memberNulls, capacity);
      byte[] kinds = value.memberKinds == null
          ? new byte[capacity] : Arrays.copyOf(value.memberKinds, capacity);
      value.memberValues = values;
      value.memberHighs = highs;
      value.memberDescriptors = descriptors;
      value.memberNulls = nulls;
      value.memberKinds = kinds;
      return true;
    } catch (OutOfMemoryError exhausted) {
      return false;
    }
  }

  static boolean set(
      SqlBooleanPredicateProgram value, int leaf, long[] highs, long[] values,
      int[] descriptors, boolean[] nulls, byte[] kinds, int count, boolean negated) {
    if (!ensure(value, value.memberCount + count)) return false;
    value.leafTests[leaf] = SqlBooleanPredicateProgram.TEST_MEMBERSHIP;
    value.leafNegated[leaf] = negated;
    value.memberOffsets[leaf] = value.memberCount;
    value.memberCounts[leaf] = count;
    System.arraycopy(values, 0, value.memberValues, value.memberCount, count);
    System.arraycopy(highs, 0, value.memberHighs, value.memberCount, count);
    System.arraycopy(descriptors, 0, value.memberDescriptors, value.memberCount, count);
    System.arraycopy(nulls, 0, value.memberNulls, value.memberCount, count);
    if (kinds == null) {
      for (int member = 0; member < count; member++) {
        value.memberKinds[value.memberCount + member] = (byte) (nulls[member]
            ? SqlScalarExpression.NULL : SqlScalarExpression.LITERAL);
      }
    } else {
      System.arraycopy(kinds, 0, value.memberKinds, value.memberCount, count);
    }
    value.memberCount += count;
    return true;
  }
}
