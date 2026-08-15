package io.riverdb.sql;

/** Flat bounded postfix and literal storage for post-aggregate predicates. */
final class SqlHavingPredicates {
  static final int MAXIMUM_PREDICATES = 8;
  static final int MAXIMUM_NODES = SqlScalarExpression.MAXIMUM_NODES;
  static final int MAXIMUM_MEMBERS = SqlCommand.MAXIMUM_LITERAL_MEMBERSHIP_VALUES;

  private final byte[] operators = new byte[MAXIMUM_NODES];
  private final long[] operands = new long[MAXIMUM_NODES];
  private final int[] nodeDescriptors = new int[MAXIMUM_NODES];
  private final byte[] nodeOffsets = new byte[MAXIMUM_PREDICATES];
  private final byte[] nodeCounts = new byte[MAXIMUM_PREDICATES];
  private final SqlComparison[] comparisons = new SqlComparison[MAXIMUM_PREDICATES];
  private final long[] values = new long[MAXIMUM_PREDICATES];
  private final long[] lowerValues = new long[MAXIMUM_PREDICATES];
  private final long[] upperValues = new long[MAXIMUM_PREDICATES];
  private final int[] valueDescriptors = new int[MAXIMUM_PREDICATES];
  private final int[] upperDescriptors = new int[MAXIMUM_PREDICATES];
  private final boolean[] valueNulls = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] upperNulls = new boolean[MAXIMUM_PREDICATES];
  private final short[] memberOffsets = new short[MAXIMUM_PREDICATES];
  private final short[] memberCounts = new short[MAXIMUM_PREDICATES];
  private final long[] members = new long[MAXIMUM_MEMBERS];
  private final boolean[] membershipNulls = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] nullPredicates = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] nullNegated = new boolean[MAXIMUM_PREDICATES];
  private final boolean[] disjunctions = new boolean[MAXIMUM_PREDICATES];
  private int predicateCount;
  private int nodeCount;
  private int memberCount;

  void reset() {
    for (int node = 0; node < nodeCount; node++) {
      operators[node] = 0;
      operands[node] = 0;
      nodeDescriptors[node] = 0;
    }
    for (int predicate = 0; predicate < predicateCount; predicate++) {
      nodeOffsets[predicate] = 0;
      nodeCounts[predicate] = 0;
      comparisons[predicate] = null;
      values[predicate] = 0;
      lowerValues[predicate] = 0;
      upperValues[predicate] = 0;
      valueDescriptors[predicate] = 0;
      upperDescriptors[predicate] = 0;
      valueNulls[predicate] = false;
      upperNulls[predicate] = false;
      memberOffsets[predicate] = 0;
      memberCounts[predicate] = 0;
      membershipNulls[predicate] = false;
      nullPredicates[predicate] = false;
      nullNegated[predicate] = false;
      disjunctions[predicate] = false;
    }
    for (int index = 0; index < memberCount; index++) members[index] = 0;
    predicateCount = 0;
    nodeCount = 0;
    memberCount = 0;
  }

  void copyFrom(SqlHavingPredicates source) {
    reset();
    predicateCount = source.predicateCount;
    nodeCount = source.nodeCount;
    memberCount = source.memberCount;
    System.arraycopy(source.operators, 0, operators, 0, nodeCount);
    System.arraycopy(source.operands, 0, operands, 0, nodeCount);
    System.arraycopy(source.nodeDescriptors, 0, nodeDescriptors, 0, nodeCount);
    System.arraycopy(source.nodeOffsets, 0, nodeOffsets, 0, predicateCount);
    System.arraycopy(source.nodeCounts, 0, nodeCounts, 0, predicateCount);
    System.arraycopy(source.comparisons, 0, comparisons, 0, predicateCount);
    System.arraycopy(source.values, 0, values, 0, predicateCount);
    System.arraycopy(source.lowerValues, 0, lowerValues, 0, predicateCount);
    System.arraycopy(source.upperValues, 0, upperValues, 0, predicateCount);
    System.arraycopy(source.valueDescriptors, 0, valueDescriptors, 0, predicateCount);
    System.arraycopy(source.upperDescriptors, 0, upperDescriptors, 0, predicateCount);
    System.arraycopy(source.valueNulls, 0, valueNulls, 0, predicateCount);
    System.arraycopy(source.upperNulls, 0, upperNulls, 0, predicateCount);
    System.arraycopy(source.memberOffsets, 0, memberOffsets, 0, predicateCount);
    System.arraycopy(source.memberCounts, 0, memberCounts, 0, predicateCount);
    System.arraycopy(source.members, 0, members, 0, memberCount);
    System.arraycopy(source.membershipNulls, 0, membershipNulls, 0, predicateCount);
    System.arraycopy(source.nullPredicates, 0, nullPredicates, 0, predicateCount);
    System.arraycopy(source.nullNegated, 0, nullNegated, 0, predicateCount);
    System.arraycopy(source.disjunctions, 0, disjunctions, 0, predicateCount);
  }

  int appendExpression(SqlScalarExpression expression) {
    if (predicateCount >= MAXIMUM_PREDICATES
        || expression == null
        || nodeCount > MAXIMUM_NODES - expression.nodeCount()) {
      return -1;
    }
    int predicate = predicateCount++;
    nodeOffsets[predicate] = (byte) nodeCount;
    nodeCounts[predicate] = (byte) expression.nodeCount();
    for (int node = 0; node < expression.nodeCount(); node++) {
      operators[nodeCount] = (byte) expression.operator(node);
      operands[nodeCount] = expression.operand(node);
      nodeDescriptors[nodeCount++] = expression.typeDescriptor(node);
    }
    return predicate;
  }

  boolean setComparison(
      int predicate,
      SqlComparison comparison,
      long value,
      int descriptor,
      boolean nullValue) {
    if (!valid(predicate) || comparison == null) return false;
    comparisons[predicate] = comparison;
    values[predicate] = value;
    valueDescriptors[predicate] = descriptor;
    valueNulls[predicate] = nullValue;
    return true;
  }

  boolean setRange(
      int predicate,
      long lower,
      int lowerDescriptor,
      boolean lowerNull,
      long upper,
      int upperDescriptor,
      boolean upperNull) {
    if (!valid(predicate)) {
      return false;
    }
    comparisons[predicate] = SqlComparison.HALF_OPEN_RANGE;
    lowerValues[predicate] = lower;
    upperValues[predicate] = upper;
    valueDescriptors[predicate] = lowerDescriptor;
    upperDescriptors[predicate] = upperDescriptor;
    valueNulls[predicate] = lowerNull;
    upperNulls[predicate] = upperNull;
    return true;
  }

  boolean setMembership(
      int predicate,
      long[] source,
      int count,
      boolean hasNull,
      boolean negated,
      int descriptor) {
    if (!valid(predicate) || source == null || count < 0
        || count > source.length || count > MAXIMUM_MEMBERS - memberCount) {
      return false;
    }
    comparisons[predicate] = negated ? SqlComparison.NOT_IN : SqlComparison.IN;
    valueDescriptors[predicate] = descriptor;
    memberOffsets[predicate] = (short) memberCount;
    memberCounts[predicate] = (short) count;
    System.arraycopy(source, 0, members, memberCount, count);
    memberCount += count;
    membershipNulls[predicate] = hasNull;
    return true;
  }

  boolean setNull(int predicate, boolean negated) {
    if (!valid(predicate)) return false;
    nullPredicates[predicate] = true;
    nullNegated[predicate] = negated;
    return true;
  }

  void setDisjunction(int predicate) {
    if (valid(predicate)) disjunctions[predicate] = true;
  }

  private boolean valid(int predicate) {
    return predicate >= 0 && predicate < predicateCount;
  }

  int predicateCount() { return predicateCount; }
  int nodeCount(int predicate) { return Byte.toUnsignedInt(nodeCounts[predicate]); }
  int operator(int predicate, int node) {
    return Byte.toUnsignedInt(operators[slot(predicate, node)]);
  }
  long operand(int predicate, int node) { return operands[slot(predicate, node)]; }
  int nodeDescriptor(int predicate, int node) {
    return nodeDescriptors[slot(predicate, node)];
  }
  SqlComparison comparison(int predicate) { return comparisons[predicate]; }
  long value(int predicate) { return values[predicate]; }
  long lower(int predicate) { return lowerValues[predicate]; }
  long upper(int predicate) { return upperValues[predicate]; }
  int valueDescriptor(int predicate) { return valueDescriptors[predicate]; }
  int upperDescriptor(int predicate) { return upperDescriptors[predicate]; }
  boolean valueNull(int predicate) { return valueNulls[predicate]; }
  boolean upperNull(int predicate) { return upperNulls[predicate]; }
  int memberCount(int predicate) { return Short.toUnsignedInt(memberCounts[predicate]); }
  long member(int predicate, int member) {
    return members[Short.toUnsignedInt(memberOffsets[predicate]) + member];
  }
  boolean membershipHasNull(int predicate) { return membershipNulls[predicate]; }
  boolean nullPredicate(int predicate) { return nullPredicates[predicate]; }
  boolean nullNegated(int predicate) { return nullNegated[predicate]; }
  boolean disjunction(int predicate) { return disjunctions[predicate]; }

  private int slot(int predicate, int node) {
    return Byte.toUnsignedInt(nodeOffsets[predicate]) + node;
  }
}
