package io.riverdb.sql;

/** Bounded Boolean tree and shared scalar/literal arenas for one SQL predicate. */
public final class SqlBooleanPredicateProgram {
  public static final int MAXIMUM_LEAVES = 8;
  public static final int MAXIMUM_SCALAR_NODES = 32;
  public static final int MAXIMUM_BOOLEAN_NODES = 32;
  public static final int MAXIMUM_MEMBERS = 256;
  public static final int MAXIMUM_DEPTH = 16;

  public static final int BOOLEAN_LEAF = 1;
  public static final int BOOLEAN_AND = 2;
  public static final int BOOLEAN_OR = 3;
  public static final int BOOLEAN_NOT = 4;

  public static final int TEST_COMPARISON = 1;
  public static final int TEST_NULL = 2;
  public static final int TEST_TRUTH = 3;
  public static final int TEST_BETWEEN = 4;
  public static final int TEST_MEMBERSHIP = 5;
  public static final int TEST_BOOLEAN = 6;

  public static final int TRUTH_TRUE = 1;
  public static final int TRUTH_FALSE = 2;
  public static final int TRUTH_UNKNOWN = 3;

  public static final int PROGRAM_LEFT = 0;
  public static final int PROGRAM_RIGHT = 1;
  public static final int PROGRAM_LOWER = 2;
  public static final int PROGRAM_UPPER = 3;
  private static final int PROGRAMS_PER_LEAF = 4;
  private static final int INITIAL_MEMBER_CAPACITY = 16;

  private final byte[] scalarOperators = new byte[MAXIMUM_SCALAR_NODES];
  private final long[] scalarOperands = new long[MAXIMUM_SCALAR_NODES];
  private final int[] scalarDescriptors = new int[MAXIMUM_SCALAR_NODES];
  private final byte[] programOffsets =
      new byte[MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
  private final byte[] programCounts =
      new byte[MAXIMUM_LEAVES * PROGRAMS_PER_LEAF];
  private final byte[] leafTests = new byte[MAXIMUM_LEAVES];
  private final SqlComparison[] comparisons = new SqlComparison[MAXIMUM_LEAVES];
  private final boolean[] leafNegated = new boolean[MAXIMUM_LEAVES];
  private final short[] memberOffsets = new short[MAXIMUM_LEAVES];
  private final short[] memberCounts = new short[MAXIMUM_LEAVES];
  private long[] memberValues;
  private int[] memberDescriptors;
  private boolean[] memberNulls;
  private final byte[] booleanOperators = new byte[MAXIMUM_BOOLEAN_NODES];
  private final byte[] booleanLeft = new byte[MAXIMUM_BOOLEAN_NODES];
  private final byte[] booleanRight = new byte[MAXIMUM_BOOLEAN_NODES];
  private final byte[] booleanDepth = new byte[MAXIMUM_BOOLEAN_NODES];
  private int scalarNodeCount;
  private int leafCount;
  private int memberCount;
  private int booleanNodeCount;
  private int root = -1;

  public void reset() {
    for (int node = 0; node < scalarNodeCount; node++) {
      scalarOperators[node] = 0;
      scalarOperands[node] = 0;
      scalarDescriptors[node] = 0;
    }
    for (int leaf = 0; leaf < leafCount; leaf++) {
      int slot = leaf * PROGRAMS_PER_LEAF;
      for (int program = 0; program < PROGRAMS_PER_LEAF; program++) {
        programOffsets[slot + program] = 0;
        programCounts[slot + program] = 0;
      }
      leafTests[leaf] = 0;
      comparisons[leaf] = null;
      leafNegated[leaf] = false;
      memberOffsets[leaf] = 0;
      memberCounts[leaf] = 0;
    }
    for (int member = 0; member < memberCount; member++) {
      memberValues[member] = 0;
      memberDescriptors[member] = 0;
      memberNulls[member] = false;
    }
    for (int node = 0; node < booleanNodeCount; node++) {
      booleanOperators[node] = 0;
      booleanLeft[node] = 0;
      booleanRight[node] = 0;
      booleanDepth[node] = 0;
    }
    scalarNodeCount = 0;
    leafCount = 0;
    memberCount = 0;
    booleanNodeCount = 0;
    root = -1;
  }

  public void copyFrom(SqlBooleanPredicateProgram source) {
    reset();
    scalarNodeCount = source.scalarNodeCount;
    leafCount = source.leafCount;
    int sourceMemberCount = source.memberCount;
    booleanNodeCount = source.booleanNodeCount;
    root = source.root;
    System.arraycopy(source.scalarOperators, 0, scalarOperators, 0, scalarNodeCount);
    System.arraycopy(source.scalarOperands, 0, scalarOperands, 0, scalarNodeCount);
    System.arraycopy(source.scalarDescriptors, 0, scalarDescriptors, 0, scalarNodeCount);
    int programCount = leafCount * PROGRAMS_PER_LEAF;
    System.arraycopy(source.programOffsets, 0, programOffsets, 0, programCount);
    System.arraycopy(source.programCounts, 0, programCounts, 0, programCount);
    System.arraycopy(source.leafTests, 0, leafTests, 0, leafCount);
    System.arraycopy(source.comparisons, 0, comparisons, 0, leafCount);
    System.arraycopy(source.leafNegated, 0, leafNegated, 0, leafCount);
    System.arraycopy(source.memberOffsets, 0, memberOffsets, 0, leafCount);
    System.arraycopy(source.memberCounts, 0, memberCounts, 0, leafCount);
    ensureMembers(sourceMemberCount);
    memberCount = sourceMemberCount;
    if (memberValues != null && memberCount > 0) {
      System.arraycopy(source.memberValues, 0, memberValues, 0, memberCount);
      System.arraycopy(source.memberDescriptors, 0, memberDescriptors, 0, memberCount);
      System.arraycopy(source.memberNulls, 0, memberNulls, 0, memberCount);
    }
    System.arraycopy(source.booleanOperators, 0, booleanOperators, 0, booleanNodeCount);
    System.arraycopy(source.booleanLeft, 0, booleanLeft, 0, booleanNodeCount);
    System.arraycopy(source.booleanRight, 0, booleanRight, 0, booleanNodeCount);
    System.arraycopy(source.booleanDepth, 0, booleanDepth, 0, booleanNodeCount);
  }

  public int appendLeaf(SqlScalarExpression left) {
    if (leafCount >= MAXIMUM_LEAVES) return -1;
    int leaf = leafCount++;
    if (!appendProgram(leaf, PROGRAM_LEFT, left)) {
      leafCount--;
      return -1;
    }
    return leaf;
  }

  public boolean setComparison(
      int leaf, SqlComparison comparison, SqlScalarExpression right) {
    if (!validLeaf(leaf) || comparison == null
        || !appendProgram(leaf, PROGRAM_RIGHT, right)) return false;
    leafTests[leaf] = TEST_COMPARISON;
    comparisons[leaf] = comparison;
    return true;
  }

  public boolean setNull(int leaf, boolean negated) {
    if (!validLeaf(leaf)) return false;
    leafTests[leaf] = TEST_NULL;
    leafNegated[leaf] = negated;
    return true;
  }

  public boolean setTruth(int leaf, int truth, boolean negated) {
    if (!validLeaf(leaf)
        || truth < TRUTH_TRUE || truth > TRUTH_UNKNOWN) return false;
    leafTests[leaf] = TEST_TRUTH;
    comparisons[leaf] = truth == TRUTH_TRUE ? SqlComparison.EQUAL
        : truth == TRUTH_FALSE ? SqlComparison.NOT_EQUAL : null;
    leafNegated[leaf] = negated;
    return true;
  }

  public boolean setBoolean(int leaf) {
    if (!validLeaf(leaf)) return false;
    leafTests[leaf] = TEST_BOOLEAN;
    return true;
  }

  public boolean setBetween(
      int leaf,
      SqlScalarExpression lower,
      SqlScalarExpression upper,
      boolean negated) {
    if (!validLeaf(leaf) || !appendProgram(leaf, PROGRAM_LOWER, lower)
        || !appendProgram(leaf, PROGRAM_UPPER, upper)) return false;
    leafTests[leaf] = TEST_BETWEEN;
    leafNegated[leaf] = negated;
    return true;
  }

  public boolean setMembership(
      int leaf,
      long[] values,
      int[] descriptors,
      boolean[] nulls,
      int count,
      boolean negated) {
    if (!validLeaf(leaf) || values == null || descriptors == null || nulls == null
        || count < 0 || count > values.length || count > descriptors.length
        || count > nulls.length || count > MAXIMUM_MEMBERS - memberCount) {
      return false;
    }
    leafTests[leaf] = TEST_MEMBERSHIP;
    leafNegated[leaf] = negated;
    ensureMembers(memberCount + count);
    memberOffsets[leaf] = (short) memberCount;
    memberCounts[leaf] = (short) count;
    System.arraycopy(values, 0, memberValues, memberCount, count);
    System.arraycopy(descriptors, 0, memberDescriptors, memberCount, count);
    System.arraycopy(nulls, 0, memberNulls, memberCount, count);
    memberCount += count;
    return true;
  }

  private void ensureMembers(int required) {
    if (required == 0 || memberValues != null && required <= memberValues.length) return;
    int capacity = memberValues == null
        ? Math.min(INITIAL_MEMBER_CAPACITY, MAXIMUM_MEMBERS)
        : memberValues.length;
    while (capacity < required) capacity = Math.min(MAXIMUM_MEMBERS, capacity * 2);
    long[] nextValues = new long[capacity];
    int[] nextDescriptors = new int[capacity];
    boolean[] nextNulls = new boolean[capacity];
    if (memberValues != null && memberCount > 0) {
      System.arraycopy(memberValues, 0, nextValues, 0, memberCount);
      System.arraycopy(memberDescriptors, 0, nextDescriptors, 0, memberCount);
      System.arraycopy(memberNulls, 0, nextNulls, 0, memberCount);
    }
    memberValues = nextValues;
    memberDescriptors = nextDescriptors;
    memberNulls = nextNulls;
  }

  public int appendBoolean(int operator, int left, int right) {
    if (booleanNodeCount >= MAXIMUM_BOOLEAN_NODES
        || operator < BOOLEAN_LEAF || operator > BOOLEAN_NOT
        || !validBooleanChildren(operator, left, right)) return -1;
    int depth = booleanDepth(operator, left, right);
    if (depth > MAXIMUM_DEPTH) return -1;
    int node = booleanNodeCount++;
    booleanOperators[node] = (byte) operator;
    booleanLeft[node] = (byte) left;
    booleanRight[node] = (byte) right;
    booleanDepth[node] = (byte) depth;
    return node;
  }

  private boolean validBooleanChildren(int operator, int left, int right) {
    if (operator == BOOLEAN_LEAF) return left >= 0 && left < leafCount;
    if (left < 0 || left >= booleanNodeCount) return false;
    return operator == BOOLEAN_NOT || right >= 0 && right < booleanNodeCount;
  }

  private int booleanDepth(int operator, int left, int right) {
    if (operator == BOOLEAN_LEAF) return 1;
    int leftDepth = Byte.toUnsignedInt(booleanDepth[left]);
    return operator == BOOLEAN_NOT ? leftDepth + 1
        : Math.max(leftDepth, Byte.toUnsignedInt(booleanDepth[right])) + 1;
  }

  public boolean finish(int rootNode) {
    if (rootNode < 0 || rootNode >= booleanNodeCount || leafCount == 0
        || Byte.toUnsignedInt(booleanDepth[rootNode]) > MAXIMUM_DEPTH) return false;
    root = rootNode;
    return true;
  }

  private boolean appendProgram(
      int leaf, int program, SqlScalarExpression expression) {
    if (expression == null || !expression.isAvailable()
        || expression.nodeCount() > MAXIMUM_SCALAR_NODES - scalarNodeCount) {
      return false;
    }
    int slot = leaf * PROGRAMS_PER_LEAF + program;
    programOffsets[slot] = (byte) scalarNodeCount;
    programCounts[slot] = (byte) expression.nodeCount();
    for (int node = 0; node < expression.nodeCount(); node++) {
      scalarOperators[scalarNodeCount] = (byte) expression.operator(node);
      scalarOperands[scalarNodeCount] = expression.operand(node);
      scalarDescriptors[scalarNodeCount++] = expression.typeDescriptor(node);
    }
    return true;
  }

  private boolean validLeaf(int leaf) {
    return leaf >= 0 && leaf < leafCount;
  }

  public boolean isAvailable() { return root >= 0; }
  boolean allColumnsQualified(SqlCommand command) {
    for (int node = 0; node < scalarNodeCount; node++) {
      if (Byte.toUnsignedInt(scalarOperators[node]) != SqlScalarExpression.COLUMN) {
        continue;
      }
      SqlIdentifier qualifier = command.projectionSymbolTable((int) scalarOperands[node]);
      if (qualifier == null || qualifier.length() == 0) return false;
    }
    return true;
  }
  public int root() { return root; }
  public int leafCount() { return leafCount; }
  public int scalarNodeCount() { return scalarNodeCount; }
  public int booleanNodeCount() { return booleanNodeCount; }
  public int memberCount() { return memberCount; }
  public int booleanOperator(int node) { return Byte.toUnsignedInt(booleanOperators[node]); }
  public int booleanLeft(int node) { return Byte.toUnsignedInt(booleanLeft[node]); }
  public int booleanRight(int node) { return Byte.toUnsignedInt(booleanRight[node]); }
  public int leafTest(int leaf) { return Byte.toUnsignedInt(leafTests[leaf]); }
  public SqlComparison comparison(int leaf) { return comparisons[leaf]; }
  public boolean leafNegated(int leaf) { return leafNegated[leaf]; }
  public int programNodeCount(int leaf, int program) {
    return programCount(leaf, program);
  }
  public int programOperator(int leaf, int program, int node) {
    return Byte.toUnsignedInt(scalarOperators[programSlot(leaf, program, node)]);
  }
  public long programOperand(int leaf, int program, int node) {
    return scalarOperands[programSlot(leaf, program, node)];
  }
  public int programDescriptor(int leaf, int program, int node) {
    return scalarDescriptors[programSlot(leaf, program, node)];
  }
  public int leafMemberCount(int leaf) { return Short.toUnsignedInt(memberCounts[leaf]); }
  public long memberValue(int leaf, int member) {
    return memberValues[Short.toUnsignedInt(memberOffsets[leaf]) + member];
  }
  public int memberDescriptor(int leaf, int member) {
    return memberDescriptors[Short.toUnsignedInt(memberOffsets[leaf]) + member];
  }
  public boolean memberNull(int leaf, int member) {
    return memberNulls[Short.toUnsignedInt(memberOffsets[leaf]) + member];
  }

  private int programCount(int leaf, int program) {
    return Byte.toUnsignedInt(programCounts[leaf * PROGRAMS_PER_LEAF + program]);
  }

  private int programSlot(int leaf, int program, int node) {
    int slot = leaf * PROGRAMS_PER_LEAF + program;
    return Byte.toUnsignedInt(programOffsets[slot]) + node;
  }
}
