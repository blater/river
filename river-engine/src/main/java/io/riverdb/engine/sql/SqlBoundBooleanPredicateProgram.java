package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Statement-owned resolved Boolean predicate with scope-aware column leaves. */
final class SqlBoundBooleanPredicateProgram {
  static final int SCOPE_LEFT = 0;
  static final int SCOPE_RIGHT = 1;
  private static final int PROGRAMS_PER_LEAF = 4;
  private static final int INITIAL_MEMBER_CAPACITY = 16;

  private final byte[] operators =
      new byte[SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES];
  private final long[] operands =
      new long[SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES];
  private final int[] descriptors =
      new int[SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES];
  private final byte[] scopes =
      new byte[SqlBooleanPredicateProgram.MAXIMUM_SCALAR_NODES];
  private final byte[] offsets = new byte[
      SqlBooleanPredicateProgram.MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
  private final byte[] counts = new byte[
      SqlBooleanPredicateProgram.MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
  private final int[] resultDescriptors = new int[
      SqlBooleanPredicateProgram.MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
  private final boolean[] unresolvedResults = new boolean[
      SqlBooleanPredicateProgram.MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
  private final int[] rawColumns = new int[
      SqlBooleanPredicateProgram.MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
  private final byte[] tests = new byte[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final SqlComparison[] comparisons =
      new SqlComparison[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final boolean[] negated =
      new boolean[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final short[] memberOffsets =
      new short[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private final short[] memberCounts =
      new short[SqlBooleanPredicateProgram.MAXIMUM_LEAVES];
  private long[] members;
  private int[] memberDescriptors;
  private boolean[] memberNulls;
  private final byte[] booleanOperators =
      new byte[SqlBooleanPredicateProgram.MAXIMUM_BOOLEAN_NODES];
  private final byte[] booleanLeft =
      new byte[SqlBooleanPredicateProgram.MAXIMUM_BOOLEAN_NODES];
  private final byte[] booleanRight =
      new byte[SqlBooleanPredicateProgram.MAXIMUM_BOOLEAN_NODES];
  private int nodeCount;
  private int leafCount;
  private int memberCount;
  private int booleanNodeCount;
  private int root = -1;

  void reset() {
    for (int node = 0; node < nodeCount; node++) {
      operators[node] = 0;
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
      memberOffsets[leaf] = 0;
      memberCounts[leaf] = 0;
    }
    for (int member = 0; member < memberCount; member++) {
      members[member] = 0;
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

  void begin(SqlBooleanPredicateProgram source) {
    reset();
    leafCount = source.leafCount();
    int sourceMemberCount = source.memberCount();
    ensureMembers(sourceMemberCount);
    memberCount = sourceMemberCount;
    booleanNodeCount = source.booleanNodeCount();
    root = source.root();
    for (int leaf = 0; leaf < leafCount; leaf++) {
      tests[leaf] = (byte) source.leafTest(leaf);
      comparisons[leaf] = source.comparison(leaf);
      negated[leaf] = source.leafNegated(leaf);
      memberOffsets[leaf] = (short) copyMembers(source, leaf);
      memberCounts[leaf] = (short) source.leafMemberCount(leaf);
      for (int program = 0; program < PROGRAMS_PER_LEAF; program++) {
        int slot = programSlot(leaf, program);
        offsets[slot] = (byte) nodeCount;
        counts[slot] = 0;
        rawColumns[slot] = -1;
      }
    }
    for (int node = 0; node < booleanNodeCount; node++) {
      booleanOperators[node] = (byte) source.booleanOperator(node);
      booleanLeft[node] = (byte) source.booleanLeft(node);
      booleanRight[node] = (byte) source.booleanRight(node);
    }
  }

  private int copyMembers(SqlBooleanPredicateProgram source, int leaf) {
    int offset = leaf == 0 ? 0
        : Short.toUnsignedInt(memberOffsets[leaf - 1])
            + Short.toUnsignedInt(memberCounts[leaf - 1]);
    for (int member = 0; member < source.leafMemberCount(leaf); member++) {
      int slot = offset + member;
      members[slot] = source.memberValue(leaf, member);
      memberDescriptors[slot] = source.memberDescriptor(leaf, member);
      memberNulls[slot] = source.memberNull(leaf, member);
    }
    return offset;
  }

  private void ensureMembers(int required) {
    if (required == 0 || members != null && required <= members.length) return;
    int capacity = members == null ? INITIAL_MEMBER_CAPACITY : members.length;
    while (capacity < required) {
      capacity = Math.min(SqlBooleanPredicateProgram.MAXIMUM_MEMBERS, capacity * 2);
    }
    long[] nextMembers = new long[capacity];
    int[] nextDescriptors = new int[capacity];
    boolean[] nextNulls = new boolean[capacity];
    if (members != null && memberCount > 0) {
      System.arraycopy(members, 0, nextMembers, 0, memberCount);
      System.arraycopy(memberDescriptors, 0, nextDescriptors, 0, memberCount);
      System.arraycopy(memberNulls, 0, nextNulls, 0, memberCount);
    }
    members = nextMembers;
    memberDescriptors = nextDescriptors;
    memberNulls = nextNulls;
  }

  void beginProgram(int leaf, int program) {
    int slot = programSlot(leaf, program);
    offsets[slot] = (byte) nodeCount;
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
    operators[nodeCount] = (byte) operator;
    operands[nodeCount] = operand;
    descriptors[nodeCount] = descriptor;
    scopes[nodeCount] = (byte) scope;
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
  }

  boolean available() { return root >= 0; }
  int root() { return root; }
  int leafCount() { return leafCount; }
  int booleanOperator(int node) { return Byte.toUnsignedInt(booleanOperators[node]); }
  int booleanLeft(int node) { return Byte.toUnsignedInt(booleanLeft[node]); }
  int booleanRight(int node) { return Byte.toUnsignedInt(booleanRight[node]); }
  int leafTest(int leaf) { return Byte.toUnsignedInt(tests[leaf]); }
  SqlComparison comparison(int leaf) { return comparisons[leaf]; }
  boolean negated(int leaf) { return negated[leaf]; }
  int nodeCount(int leaf, int program) {
    return Byte.toUnsignedInt(counts[programSlot(leaf, program)]);
  }
  int operator(int leaf, int program, int node) {
    return Byte.toUnsignedInt(operators[nodeSlot(leaf, program, node)]);
  }
  long operand(int leaf, int program, int node) {
    return operands[nodeSlot(leaf, program, node)];
  }
  int descriptor(int leaf, int program, int node) {
    return descriptors[nodeSlot(leaf, program, node)];
  }
  int scope(int leaf, int program, int node) {
    return Byte.toUnsignedInt(scopes[nodeSlot(leaf, program, node)]);
  }
  int resultDescriptor(int leaf, int program) {
    return resultDescriptors[programSlot(leaf, program)];
  }
  int rawColumn(int leaf, int program) {
    return rawColumns[programSlot(leaf, program)];
  }
  int memberCount(int leaf) { return Short.toUnsignedInt(memberCounts[leaf]); }
  long member(int leaf, int member) { return members[memberSlot(leaf, member)]; }
  int memberDescriptor(int leaf, int member) {
    return memberDescriptors[memberSlot(leaf, member)];
  }
  boolean memberNull(int leaf, int member) {
    return memberNulls[memberSlot(leaf, member)];
  }

  private int memberSlot(int leaf, int member) {
    return Short.toUnsignedInt(memberOffsets[leaf]) + member;
  }

  private int nodeSlot(int leaf, int program, int node) {
    return Byte.toUnsignedInt(offsets[programSlot(leaf, program)]) + node;
  }

  private static int programSlot(int leaf, int program) {
    return leaf * PROGRAMS_PER_LEAF + program;
  }
}
