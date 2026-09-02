package io.riverdb.sql;

import java.util.Arrays;

/** Atomic geometric growth for predicate leaf, scalar, and Boolean arenas. */
final class SqlPredicateCapacity {
  private static final int PROGRAMS_PER_LEAF = 4;
  private SqlPredicateCapacity() { }

  static boolean ensureCopy(
      SqlBooleanPredicateProgram value,
      int scalars, int leaves, int booleans, int members) {
    return ensureScalars(value, scalars)
        && ensureLeaves(value, leaves)
        && ensureBooleans(value, booleans)
        && SqlPredicateMembers.ensure(value, members);
  }

  static int programCount(
      SqlBooleanPredicateProgram value, int leaf, int program) {
    return value.programCounts[leaf * PROGRAMS_PER_LEAF + program];
  }

  static int programSlot(
      SqlBooleanPredicateProgram value, int leaf, int program, int node) {
    return value.programOffsets[leaf * PROGRAMS_PER_LEAF + program] + node;
  }

  static boolean ensureScalars(SqlBooleanPredicateProgram value, int required) {
    if (required <= value.scalarOperators.length) return true;
    int capacity = grow(value.scalarOperators.length, required,
        SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES);
    try {
      byte[] operators = Arrays.copyOf(value.scalarOperators, capacity);
      long[] operandHighs = Arrays.copyOf(value.scalarOperandHighs, capacity);
      long[] operands = Arrays.copyOf(value.scalarOperands, capacity);
      int[] descriptors = Arrays.copyOf(value.scalarDescriptors, capacity);
      value.scalarOperators = operators;
      value.scalarOperandHighs = operandHighs;
      value.scalarOperands = operands;
      value.scalarDescriptors = descriptors;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  static boolean ensureLeaves(SqlBooleanPredicateProgram value, int required) {
    if (required <= value.leafTests.length) return true;
    int capacity = grow(value.leafTests.length, required,
        SqlBooleanPredicateProgram.MAXIMUM_LEAVES);
    try {
      int programs = capacity * 4;
      int[] offsets = Arrays.copyOf(value.programOffsets, programs);
      int[] counts = Arrays.copyOf(value.programCounts, programs);
      byte[] tests = Arrays.copyOf(value.leafTests, capacity);
      SqlComparison[] comparisons = Arrays.copyOf(value.comparisons, capacity);
      boolean[] negated = Arrays.copyOf(value.leafNegated, capacity);
      int[] edges = Arrays.copyOf(value.subqueryEdges, capacity);
      int[] memberOffsets = Arrays.copyOf(value.memberOffsets, capacity);
      int[] memberCounts = Arrays.copyOf(value.memberCounts, capacity);
      value.programOffsets = offsets;
      value.programCounts = counts;
      value.leafTests = tests;
      value.comparisons = comparisons;
      value.leafNegated = negated;
      value.subqueryEdges = edges;
      value.memberOffsets = memberOffsets;
      value.memberCounts = memberCounts;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  static boolean ensureBooleans(SqlBooleanPredicateProgram value, int required) {
    if (required <= value.booleanOperators.length) return true;
    int capacity = grow(value.booleanOperators.length, required,
        SqlBooleanPredicateProgram.MAXIMUM_BOOLEAN_NODES);
    try {
      byte[] operators = Arrays.copyOf(value.booleanOperators, capacity);
      int[] left = Arrays.copyOf(value.booleanLeft, capacity);
      int[] right = Arrays.copyOf(value.booleanRight, capacity);
      int[] depth = Arrays.copyOf(value.booleanDepth, capacity);
      value.booleanOperators = operators;
      value.booleanLeft = left;
      value.booleanRight = right;
      value.booleanDepth = depth;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private static int grow(int current, int required, int maximum) {
    int capacity = current;
    while (capacity < required) capacity = Math.min(maximum, capacity * 2);
    return capacity;
  }
}
