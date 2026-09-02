package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Bounded Boolean tree and shared scalar/literal arenas for one SQL predicate. */
public final class SqlBooleanPredicateProgram {
  public static final int MAXIMUM_LEAVES = SqlShapeLimits.MAX_PREDICATE_LEAVES;
  public static final int MAXIMUM_SCALAR_NODES = SqlShapeLimits.MAX_EXPRESSION_NODES;
  public static final int MAXIMUM_BOOLEAN_NODES = SqlShapeLimits.MAX_EXPRESSION_NODES;
  public static final int MAXIMUM_MEMBERS = 256;
  public static final int MAXIMUM_DEPTH = SqlShapeLimits.MAX_EXPRESSION_DEPTH;

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
  public static final int TEST_SUBQUERY_EXISTS = 7;
  public static final int TEST_SUBQUERY_COMPARISON = 8;
  public static final int TEST_SUBQUERY_MEMBERSHIP = 9;

  public static final int TRUTH_TRUE = 1;
  public static final int TRUTH_FALSE = 2;
  public static final int TRUTH_UNKNOWN = 3;

  public static final int PROGRAM_LEFT = 0;
  public static final int PROGRAM_RIGHT = 1;
  public static final int PROGRAM_LOWER = 2;
  public static final int PROGRAM_UPPER = 3;
  private static final int PROGRAMS_PER_LEAF = 4;

  byte[] scalarOperators = new byte[32];
  long[] scalarOperandHighs = new long[32];
  long[] scalarOperands = new long[32];
  int[] scalarDescriptors = new int[32];
  int[] programOffsets = new int[8 * PROGRAMS_PER_LEAF];
  int[] programCounts = new int[8 * PROGRAMS_PER_LEAF];
  byte[] leafTests = new byte[8];
  SqlComparison[] comparisons = new SqlComparison[8];
  boolean[] leafNegated = new boolean[8];
  int[] subqueryEdges = new int[8];
  int[] memberOffsets = new int[8];
  int[] memberCounts = new int[8];
  long[] memberValues;
  long[] memberHighs;
  int[] memberDescriptors;
  boolean[] memberNulls;
  byte[] memberKinds;
  byte[] booleanOperators = new byte[16];
  int[] booleanLeft = new int[16];
  int[] booleanRight = new int[16];
  int[] booleanDepth = new int[16];
  int scalarNodeCount;
  int leafCount;
  int memberCount;
  int booleanNodeCount;
  int root = -1;

  public void reset() {
    for (int node = 0; node < scalarNodeCount; node++) {
      scalarOperators[node] = 0;
      scalarOperandHighs[node] = 0;
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
      subqueryEdges[leaf] = -1;
      memberOffsets[leaf] = 0;
      memberCounts[leaf] = 0;
    }
    for (int member = 0; member < memberCount; member++) {
      memberValues[member] = 0;
      memberHighs[member] = 0;
      memberDescriptors[member] = 0;
      memberNulls[member] = false;
      memberKinds[member] = 0;
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

  public StatusCode copyFrom(SqlBooleanPredicateProgram source) {
    return SqlPredicateCopy.copy(this, source);
  }

  public int appendLeaf(SqlScalarExpression left) {
    if (leafCount >= MAXIMUM_LEAVES || !SqlPredicateCapacity.ensureLeaves(this, leafCount + 1)) {
      return -1;
    }
    int leaf = leafCount++;
    subqueryEdges[leaf] = -1;
    if (!appendProgram(leaf, PROGRAM_LEFT, left)) {
      leafCount--;
      return -1;
    }
    return leaf;
  }

  int appendSubqueryExists(int edge) {
    if (leafCount >= MAXIMUM_LEAVES || edge < 0 || edge >= SqlQuery.MAXIMUM_EDGES
        || !SqlPredicateCapacity.ensureLeaves(this, leafCount + 1)) {
      return -1;
    }
    int leaf = leafCount++;
    subqueryEdges[leaf] = edge;
    int first = leaf * PROGRAMS_PER_LEAF;
    for (int program = 0; program < PROGRAMS_PER_LEAF; program++) {
      programOffsets[first + program] = scalarNodeCount;
      programCounts[first + program] = 0;
    }
    leafTests[leaf] = TEST_SUBQUERY_EXISTS;
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
      long[] highs,
      long[] values,
      int[] descriptors,
      boolean[] nulls,
      int count,
      boolean negated) {
    if (!validLeaf(leaf) || highs == null || values == null
        || descriptors == null || nulls == null
        || count < 0 || count > highs.length || count > values.length
        || count > descriptors.length
        || count > nulls.length || count > MAXIMUM_MEMBERS - memberCount) {
      return false;
    }
    return SqlPredicateMembers.set(
        this, leaf, highs, values, descriptors, nulls, null, count, negated);
  }

  boolean setMembership(
      int leaf, long[] highs, long[] values, int[] descriptors,
      boolean[] nulls, byte[] kinds, int count, boolean negated) {
    if (!validLeaf(leaf) || kinds == null || count > kinds.length) return false;
    return SqlPredicateMembers.set(
        this, leaf, highs, values, descriptors, nulls, kinds, count, negated);
  }

  boolean setSubqueryComparison(
      int leaf, SqlComparison comparison, int edge) {
    if (!validLeaf(leaf) || comparison == null
        || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN
        || edge < 0 || edge >= SqlQuery.MAXIMUM_EDGES) return false;
    leafTests[leaf] = TEST_SUBQUERY_COMPARISON;
    comparisons[leaf] = comparison;
    subqueryEdges[leaf] = edge;
    return true;
  }

  boolean setSubqueryMembership(int leaf, int edge, boolean negated) {
    if (!validLeaf(leaf) || edge < 0 || edge >= SqlQuery.MAXIMUM_EDGES) return false;
    leafTests[leaf] = TEST_SUBQUERY_MEMBERSHIP;
    leafNegated[leaf] = negated;
    subqueryEdges[leaf] = edge;
    return true;
  }

  public int appendBoolean(int operator, int left, int right) {
    if (booleanNodeCount >= MAXIMUM_BOOLEAN_NODES
        || !SqlPredicateCapacity.ensureBooleans(this, booleanNodeCount + 1)
        || operator < BOOLEAN_LEAF || operator > BOOLEAN_NOT
        || !validBooleanChildren(operator, left, right)) return -1;
    int depth = booleanDepth(operator, left, right);
    if (depth > MAXIMUM_DEPTH) return -1;
    int node = booleanNodeCount++;
    booleanOperators[node] = (byte) operator;
    booleanLeft[node] = left;
    booleanRight[node] = right;
    booleanDepth[node] = depth;
    return node;
  }

  private boolean validBooleanChildren(int operator, int left, int right) {
    if (operator == BOOLEAN_LEAF) return left >= 0 && left < leafCount;
    if (left < 0 || left >= booleanNodeCount) return false;
    return operator == BOOLEAN_NOT || right >= 0 && right < booleanNodeCount;
  }

  private int booleanDepth(int operator, int left, int right) {
    if (operator == BOOLEAN_LEAF) return 1;
    int leftDepth = booleanDepth[left];
    return operator == BOOLEAN_NOT ? leftDepth + 1
        : Math.max(leftDepth, booleanDepth[right]) + 1;
  }

  public boolean finish(int rootNode) {
    if (rootNode < 0 || rootNode >= booleanNodeCount || leafCount == 0
        || booleanDepth[rootNode] > MAXIMUM_DEPTH) return false;
    for (int leaf = 0; leaf < leafCount; leaf++) {
      int test = Byte.toUnsignedInt(leafTests[leaf]);
      boolean subquery = test >= TEST_SUBQUERY_EXISTS
          && test <= TEST_SUBQUERY_MEMBERSHIP;
      if (subquery != (subqueryEdges[leaf] >= 0)) return false;
    }
    root = rootNode;
    return true;
  }

  private boolean appendProgram(
      int leaf, int program, SqlScalarExpression expression) {
    if (expression == null || !expression.isAvailable()
        || expression.nodeCount() > MAXIMUM_SCALAR_NODES - scalarNodeCount
        || !SqlPredicateCapacity.ensureScalars(
            this, scalarNodeCount + expression.nodeCount())) {
      return false;
    }
    int slot = leaf * PROGRAMS_PER_LEAF + program;
    programOffsets[slot] = scalarNodeCount;
    programCounts[slot] = expression.nodeCount();
    for (int node = 0; node < expression.nodeCount(); node++) {
      scalarOperators[scalarNodeCount] = (byte) expression.operator(node);
      scalarOperandHighs[scalarNodeCount] = expression.operandHigh(node);
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
  public int booleanLeft(int node) { return booleanLeft[node]; }
  public int booleanRight(int node) { return booleanRight[node]; }
  public int leafTest(int leaf) { return Byte.toUnsignedInt(leafTests[leaf]); }
  public SqlComparison comparison(int leaf) { return comparisons[leaf]; }
  public boolean leafNegated(int leaf) { return leafNegated[leaf]; }
  public int subqueryEdge(int leaf) {
    return leaf >= 0 && leaf < leafCount ? subqueryEdges[leaf] : -1;
  }
  public int programNodeCount(int leaf, int program) {
    return SqlPredicateCapacity.programCount(this, leaf, program);
  }
  public int programOperator(int leaf, int program, int node) {
    return Byte.toUnsignedInt(scalarOperators[
        SqlPredicateCapacity.programSlot(this, leaf, program, node)]);
  }
  public long programOperand(int leaf, int program, int node) {
    return scalarOperands[SqlPredicateCapacity.programSlot(this, leaf, program, node)];
  }
  public long programOperandHigh(int leaf, int program, int node) {
    return scalarOperandHighs[
        SqlPredicateCapacity.programSlot(this, leaf, program, node)];
  }
  public int programDescriptor(int leaf, int program, int node) {
    return scalarDescriptors[SqlPredicateCapacity.programSlot(this, leaf, program, node)];
  }
  public int leafMemberCount(int leaf) { return memberCounts[leaf]; }
  public long memberValue(int leaf, int member) {
    return memberValues[memberOffsets[leaf] + member];
  }
  public long memberHigh(int leaf, int member) {
    return memberHighs[memberOffsets[leaf] + member];
  }
  public int memberDescriptor(int leaf, int member) {
    return memberDescriptors[memberOffsets[leaf] + member];
  }
  public boolean memberNull(int leaf, int member) {
    return memberNulls[memberOffsets[leaf] + member];
  }
  public int memberKind(int leaf, int member) {
    return Byte.toUnsignedInt(memberKinds[memberOffsets[leaf] + member]);
  }

}
