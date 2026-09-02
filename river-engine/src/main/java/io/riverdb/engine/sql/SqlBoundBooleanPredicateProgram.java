package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Statement-owned resolved Boolean predicate with scope-aware column leaves. */
final class SqlBoundBooleanPredicateProgram {
  static final int SCOPE_LEFT = 0;
  static final int SCOPE_RIGHT = 1;
  private static final int PROGRAMS_PER_LEAF = 4;
  final SqlRetainedArrayAllocator allocator;
  byte[] operators = new byte[0];
  long[] operandHighs = new long[0];
  long[] operands = new long[0];
  int[] descriptors = new int[0];
  int[] scopes = new int[0];
  int[] offsets = new int[0];
  int[] counts = new int[0];
  int[] resultDescriptors = new int[0];
  boolean[] unresolvedResults = new boolean[0];
  int[] rawColumns = new int[0];
  byte[] tests = new byte[0];
  SqlComparison[] comparisons = new SqlComparison[0];
  boolean[] negated = new boolean[0];
  int[] subqueryEdges = new int[0];
  int[] memberOffsets = new int[0];
  int[] memberCounts = new int[0];
  long[] members;
  long[] memberHighs;
  int[] memberDescriptors;
  boolean[] memberNulls;
  byte[] booleanOperators = new byte[0];
  int[] booleanLeft = new int[0];
  int[] booleanRight = new int[0];
  int nodeCount;
  int leafCount;
  int memberCount;
  int booleanNodeCount;
  int root = -1;

  SqlBoundBooleanPredicateProgram() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBoundBooleanPredicateProgram(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  void reset() {
    for (int node = 0; node < nodeCount; node++) {
      operators[node] = 0;
      operandHighs[node] = 0;
      operands[node] = 0;
      descriptors[node] = 0;
      scopes[node] = 0;
    }
    int programCount = leafCount * PROGRAMS_PER_LEAF;
    for (int program = 0; program < programCount; program++) {
      offsets[program] = 0;
      counts[program] = 0;
      resultDescriptors[program] = 0;
      unresolvedResults[program] = false;
      rawColumns[program] = -1;
    }
    for (int leaf = 0; leaf < leafCount; leaf++) {
      tests[leaf] = 0;
      comparisons[leaf] = null;
      negated[leaf] = false;
      subqueryEdges[leaf] = -1;
      memberOffsets[leaf] = 0;
      memberCounts[leaf] = 0;
    }
    for (int member = 0; member < memberCount; member++) {
      members[member] = 0;
      memberHighs[member] = 0;
      memberDescriptors[member] = 0;
      memberNulls[member] = false;
    }
    for (int node = 0; node < booleanNodeCount; node++) {
      booleanOperators[node] = 0;
      booleanLeft[node] = 0;
      booleanRight[node] = 0;
    }
    nodeCount = 0;
    leafCount = 0;
    memberCount = 0;
    booleanNodeCount = 0;
    root = -1;
  }

  StatusCode begin(SqlBooleanPredicateProgram source) {
    reset();
    return SqlBoundPredicateInitialization.begin(this, source);
  }

  void prepareLeafPrograms(int leaf) {
    for (int program = 0; program < PROGRAMS_PER_LEAF; program++) {
      int slot = programSlot(leaf, program);
      offsets[slot] = nodeCount;
      counts[slot] = 0;
      rawColumns[slot] = -1;
    }
  }

  void beginProgram(int leaf, int program) {
    int slot = programSlot(leaf, program);
    offsets[slot] = nodeCount;
    counts[slot] = 0;
    resultDescriptors[slot] = 0;
    unresolvedResults[slot] = false;
    rawColumns[slot] = -1;
  }

  void append(
      int leaf,
      int program,
      int operator,
      long operand,
      int descriptor,
      int scope) {
    append(leaf, program, operator, operand >> 63, operand, descriptor, scope);
  }

  void append(
      int leaf,
      int program,
      int operator,
      long operandHigh,
      long operand,
      int descriptor,
      int scope) {
    operators[nodeCount] = (byte) operator;
    operandHighs[nodeCount] = operandHigh;
    operands[nodeCount] = operand;
    descriptors[nodeCount] = descriptor;
    scopes[nodeCount] = scope;
    nodeCount++;
    counts[programSlot(leaf, program)]++;
  }

  void finishProgram(int leaf, int program, int descriptor, int rawColumn) {
    int slot = programSlot(leaf, program);
    resultDescriptors[slot] = descriptor;
    rawColumns[slot] = rawColumn;
  }

  void markUnresolved(int leaf, int program) {
    unresolvedResults[programSlot(leaf, program)] = true;
  }

  boolean unresolved(int leaf, int program) {
    return unresolvedResults[programSlot(leaf, program)];
  }

  void resolveDescriptor(int leaf, int program, int descriptor) {
    int slot = programSlot(leaf, program);
    resultDescriptors[slot] = descriptor;
    unresolvedResults[slot] = false;
    if (counts[slot] == 1) {
      int node = offsets[slot];
      if (operators[node] == io.riverdb.sql.SqlScalarExpression.NULL) {
        descriptors[node] = descriptor;
      }
    }
  }

  boolean available() { return root >= 0; }
  int root() { return root; }
  int leafCount() { return leafCount; }
  int booleanOperator(int node) { return Byte.toUnsignedInt(booleanOperators[node]); }
  int booleanLeft(int node) { return booleanLeft[node]; }
  int booleanRight(int node) { return booleanRight[node]; }
  int leafTest(int leaf) { return Byte.toUnsignedInt(tests[leaf]); }
  SqlComparison comparison(int leaf) { return comparisons[leaf]; }
  boolean negated(int leaf) { return negated[leaf]; }
  int subqueryEdge(int leaf) { return subqueryEdges[leaf]; }
  int nodeCount(int leaf, int program) {
    return counts[programSlot(leaf, program)];
  }
  int operator(int leaf, int program, int node) {
    return Byte.toUnsignedInt(operators[nodeSlot(leaf, program, node)]);
  }
  long operand(int leaf, int program, int node) {
    return operands[nodeSlot(leaf, program, node)];
  }
  long operandHigh(int leaf, int program, int node) {
    return operandHighs[nodeSlot(leaf, program, node)];
  }
  int descriptor(int leaf, int program, int node) {
    return descriptors[nodeSlot(leaf, program, node)];
  }
  int scope(int leaf, int program, int node) {
    return scopes[nodeSlot(leaf, program, node)];
  }
  int resultDescriptor(int leaf, int program) {
    return resultDescriptors[programSlot(leaf, program)];
  }
  int rawColumn(int leaf, int program) {
    return rawColumns[programSlot(leaf, program)];
  }
  int memberCount(int leaf) { return memberCounts[leaf]; }
  long member(int leaf, int member) { return members[memberSlot(leaf, member)]; }
  long memberHigh(int leaf, int member) {
    return memberHighs[memberSlot(leaf, member)];
  }
  int memberDescriptor(int leaf, int member) {
    return memberDescriptors[memberSlot(leaf, member)];
  }
  boolean memberNull(int leaf, int member) {
    return memberNulls[memberSlot(leaf, member)];
  }

  private int memberSlot(int leaf, int member) {
    return memberOffsets[leaf] + member;
  }

  private int nodeSlot(int leaf, int program, int node) {
    return offsets[programSlot(leaf, program)] + node;
  }

  private static int programSlot(int leaf, int program) {
    return leaf * PROGRAMS_PER_LEAF + program;
  }
}
