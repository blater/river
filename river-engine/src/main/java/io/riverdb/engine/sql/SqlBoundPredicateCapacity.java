package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Transactional actual-count storage admission for one bound Boolean program. */
final class SqlBoundPredicateCapacity {
  private static final int PROGRAMS_PER_LEAF = 4;

  private SqlBoundPredicateCapacity() { }

  static StatusCode reserve(
      SqlBoundBooleanPredicateProgram target, SqlBooleanPredicateProgram source) {
    return reserve(target, source.scalarNodeCount(), source.leafCount(),
        source.booleanNodeCount(), source.memberCount());
  }

  static StatusCode reserve(
      SqlBoundBooleanPredicateProgram target,
      int scalarCount,
      int leafCount,
      int booleanCount,
      int memberCount) {
    if (reserved(target, scalarCount, leafCount, booleanCount, memberCount)) {
      return StatusCode.OK;
    }
    Buffers buffers = new Buffers(target);
    try {
      if (!scalars(target, buffers, scalarCount)) return exhausted();
      if (!leaves(target, buffers, leafCount)) return exhausted();
      if (!booleans(target, buffers, booleanCount)) return exhausted();
      if (!members(target, buffers, memberCount)) return exhausted();
      buffers.publish(target);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return exhausted();
    }
  }

  private static boolean scalars(
      SqlBoundBooleanPredicateProgram target, Buffers buffers, int required) {
    int capacity = capacity(target.operators.length, required,
        SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES, 32);
    if (capacity < 0) return false;
    if (capacity == target.operators.length) return true;
    byte[] operators = target.allocator.bytes(capacity);
    long[] operandHighs = target.allocator.longs(capacity);
    long[] operands = target.allocator.longs(capacity);
    int[] descriptors = target.allocator.integers(capacity);
    int[] scopes = target.allocator.integers(capacity);
    buffers.operators = operators;
    buffers.operandHighs = operandHighs;
    buffers.operands = operands;
    buffers.descriptors = descriptors;
    buffers.scopes = scopes;
    return true;
  }

  private static boolean leaves(
      SqlBoundBooleanPredicateProgram target, Buffers buffers, int required) {
    int capacity = capacity(target.tests.length, required,
        SqlBooleanPredicateProgram.MAXIMUM_LEAVES, 8);
    if (capacity < 0) return false;
    int programs = capacity * PROGRAMS_PER_LEAF;
    if (capacity == target.tests.length) return true;
    int[] offsets = target.allocator.integers(programs);
    int[] counts = target.allocator.integers(programs);
    int[] descriptors = target.allocator.integers(programs);
    boolean[] unresolved = target.allocator.booleans(programs);
    int[] raw = target.allocator.integers(programs);
    byte[] tests = target.allocator.bytes(capacity);
    io.riverdb.sql.SqlComparison[] comparisons =
        target.allocator.comparisons(capacity);
    boolean[] negated = target.allocator.booleans(capacity);
    int[] edges = target.allocator.integers(capacity);
    int[] memberOffsets = target.allocator.integers(capacity);
    int[] memberCounts = target.allocator.integers(capacity);
    buffers.offsets = offsets;
    buffers.counts = counts;
    buffers.resultDescriptors = descriptors;
    buffers.unresolvedResults = unresolved;
    buffers.rawColumns = raw;
    buffers.tests = tests;
    buffers.comparisons = comparisons;
    buffers.negated = negated;
    buffers.subqueryEdges = edges;
    buffers.memberOffsets = memberOffsets;
    buffers.memberCounts = memberCounts;
    return true;
  }

  private static boolean booleans(
      SqlBoundBooleanPredicateProgram target, Buffers buffers, int required) {
    int capacity = capacity(target.booleanOperators.length, required,
        SqlBooleanPredicateProgram.MAXIMUM_BOOLEAN_NODES, 16);
    if (capacity < 0) return false;
    if (capacity == target.booleanOperators.length) return true;
    byte[] operators = target.allocator.bytes(capacity);
    int[] left = target.allocator.integers(capacity);
    int[] right = target.allocator.integers(capacity);
    buffers.booleanOperators = operators;
    buffers.booleanLeft = left;
    buffers.booleanRight = right;
    return true;
  }

  private static boolean members(
      SqlBoundBooleanPredicateProgram target, Buffers buffers, int required) {
    int capacity = capacity(target.members == null ? 0 : target.members.length,
        required, SqlBooleanPredicateProgram.MAXIMUM_MEMBERS, 16);
    if (capacity < 0) return false;
    if (target.members != null && capacity == target.members.length) return true;
    if (capacity == 0) return true;
    long[] values = target.allocator.longs(capacity);
    long[] highs = target.allocator.longs(capacity);
    int[] descriptors = target.allocator.integers(capacity);
    boolean[] nulls = target.allocator.booleans(capacity);
    buffers.members = values;
    buffers.memberHighs = highs;
    buffers.memberDescriptors = descriptors;
    buffers.memberNulls = nulls;
    return true;
  }

  private static int capacity(int current, int required, int maximum, int initial) {
    return BoundedArrayGrowth.capacity(current, required, maximum, initial);
  }

  private static boolean reserved(
      SqlBoundBooleanPredicateProgram target,
      int scalars,
      int leaves,
      int booleans,
      int members) {
    int memberCapacity = target.members == null ? 0 : target.members.length;
    return scalars >= 0 && scalars <= target.operators.length
        && leaves >= 0 && leaves <= target.tests.length
        && booleans >= 0 && booleans <= target.booleanOperators.length
        && members >= 0 && members <= memberCapacity;
  }

  private static StatusCode exhausted() { return StatusCode.RESOURCE_EXHAUSTED; }

  private static final class Buffers {
    private byte[] operators;
    private long[] operandHighs;
    private long[] operands;
    private int[] descriptors;
    private int[] scopes;
    private int[] offsets;
    private int[] counts;
    private int[] resultDescriptors;
    private boolean[] unresolvedResults;
    private int[] rawColumns;
    private byte[] tests;
    private io.riverdb.sql.SqlComparison[] comparisons;
    private boolean[] negated;
    private int[] subqueryEdges;
    private int[] memberOffsets;
    private int[] memberCounts;
    private long[] members;
    private long[] memberHighs;
    private int[] memberDescriptors;
    private boolean[] memberNulls;
    private byte[] booleanOperators;
    private int[] booleanLeft;
    private int[] booleanRight;

    private Buffers(SqlBoundBooleanPredicateProgram target) {
      operators = target.operators; operandHighs = target.operandHighs;
      operands = target.operands;
      descriptors = target.descriptors; scopes = target.scopes;
      offsets = target.offsets; counts = target.counts;
      resultDescriptors = target.resultDescriptors;
      unresolvedResults = target.unresolvedResults; rawColumns = target.rawColumns;
      tests = target.tests; comparisons = target.comparisons; negated = target.negated;
      subqueryEdges = target.subqueryEdges; memberOffsets = target.memberOffsets;
      memberCounts = target.memberCounts; members = target.members;
      memberHighs = target.memberHighs;
      memberDescriptors = target.memberDescriptors; memberNulls = target.memberNulls;
      booleanOperators = target.booleanOperators; booleanLeft = target.booleanLeft;
      booleanRight = target.booleanRight;
    }

    private void publish(SqlBoundBooleanPredicateProgram target) {
      target.operators = operators; target.operandHighs = operandHighs;
      target.operands = operands;
      target.descriptors = descriptors; target.scopes = scopes;
      target.offsets = offsets; target.counts = counts;
      target.resultDescriptors = resultDescriptors;
      target.unresolvedResults = unresolvedResults; target.rawColumns = rawColumns;
      target.tests = tests; target.comparisons = comparisons; target.negated = negated;
      target.subqueryEdges = subqueryEdges; target.memberOffsets = memberOffsets;
      target.memberCounts = memberCounts; target.members = members;
      target.memberHighs = memberHighs;
      target.memberDescriptors = memberDescriptors; target.memberNulls = memberNulls;
      target.booleanOperators = booleanOperators; target.booleanLeft = booleanLeft;
      target.booleanRight = booleanRight;
    }
  }
}
