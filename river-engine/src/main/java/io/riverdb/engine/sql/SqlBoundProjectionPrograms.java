package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlScalarExpression;

/** Statement-owned resolved postfix programs for row projections. */
final class SqlBoundProjectionPrograms {
  static final int COMPUTED_PROJECTION = Integer.MIN_VALUE + 1;
  static final int PREDICATE_LANE = TableSchema.MAXIMUM_COLUMNS;
  static final int HAVING_LANE = PREDICATE_LANE + 1;
  private static final int MAXIMUM_PROGRAMS = TableSchema.MAXIMUM_COLUMNS + 2;

  private final byte[][] operators =
      new byte[MAXIMUM_PROGRAMS][SqlScalarExpression.MAXIMUM_NODES];
  private final long[][] operands =
      new long[MAXIMUM_PROGRAMS][SqlScalarExpression.MAXIMUM_NODES];
  private final int[][] descriptors =
      new int[MAXIMUM_PROGRAMS][SqlScalarExpression.MAXIMUM_NODES];
  private final int[] nodeCounts = new int[MAXIMUM_PROGRAMS];
  private final int[] resultDescriptors = new int[MAXIMUM_PROGRAMS];
  private final int[] rawColumns = new int[MAXIMUM_PROGRAMS];
  private final byte[] mutationOperators =
      new byte[SqlScalarExpression.MAXIMUM_NODES];
  private final long[] mutationOperands =
      new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] mutationDescriptors =
      new int[SqlScalarExpression.MAXIMUM_NODES];
  private final byte[] mutationOffsets =
      new byte[SqlScalarExpression.MAXIMUM_NODES];
  private final byte[] mutationCounts =
      new byte[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] mutationResultDescriptors =
      new int[SqlScalarExpression.MAXIMUM_NODES];
  private int count;
  private int mutationCount;
  private int mutationNodeCount;

  void reset() {
    for (int program = 0; program < MAXIMUM_PROGRAMS; program++) {
      for (int node = 0; node < nodeCounts[program]; node++) {
        operators[program][node] = 0;
        operands[program][node] = 0;
        descriptors[program][node] = 0;
      }
      nodeCounts[program] = 0;
      resultDescriptors[program] = 0;
      rawColumns[program] = -1;
    }
    count = 0;
    for (int node = 0; node < mutationNodeCount; node++) {
      mutationOperators[node] = 0;
      mutationOperands[node] = 0;
      mutationDescriptors[node] = 0;
    }
    for (int program = 0; program < mutationCount; program++) {
      mutationOffsets[program] = 0;
      mutationCounts[program] = 0;
      mutationResultDescriptors[program] = 0;
    }
    mutationCount = 0;
    mutationNodeCount = 0;
  }

  void begin(int projectionCount) {
    reset();
    count = projectionCount;
    for (int index = 0; index < count; index++) {
      rawColumns[index] = -1;
    }
  }

  void beginPredicate() {
    clear(PREDICATE_LANE);
  }

  void beginMutations(int programs) {
    mutationCount = programs;
    mutationNodeCount = 0;
    for (int program = 0; program < programs; program++) {
      mutationOffsets[program] = 0;
      mutationCounts[program] = 0;
      mutationResultDescriptors[program] = 0;
    }
  }

  void beginMutation(int program) {
    mutationOffsets[program] = (byte) mutationNodeCount;
    mutationCounts[program] = 0;
  }

  void appendMutation(int program, int operator, long operand, int descriptor) {
    mutationOperators[mutationNodeCount] = (byte) operator;
    mutationOperands[mutationNodeCount] = operand;
    mutationDescriptors[mutationNodeCount++] = descriptor;
    mutationCounts[program]++;
  }

  void finishMutation(int program, int descriptor) {
    mutationResultDescriptors[program] = descriptor;
  }

  int mutationCount() { return mutationCount; }
  int mutationNodeCount(int program) { return Byte.toUnsignedInt(mutationCounts[program]); }
  int mutationOperator(int program, int node) {
    return Byte.toUnsignedInt(mutationOperators[mutationSlot(program, node)]);
  }
  long mutationOperand(int program, int node) {
    return mutationOperands[mutationSlot(program, node)];
  }
  int mutationDescriptor(int program, int node) {
    return mutationDescriptors[mutationSlot(program, node)];
  }
  int mutationResultDescriptor(int program) {
    return mutationResultDescriptors[program];
  }

  private int mutationSlot(int program, int node) {
    return Byte.toUnsignedInt(mutationOffsets[program]) + node;
  }

  void beginHaving() {
    clear(HAVING_LANE);
  }

  private void clear(int program) {
    for (int node = 0; node < nodeCounts[program]; node++) {
      operators[program][node] = 0;
      operands[program][node] = 0;
      descriptors[program][node] = 0;
    }
    nodeCounts[program] = 0;
    resultDescriptors[program] = 0;
    rawColumns[program] = -1;
  }

  boolean hasPredicate() {
    return nodeCounts[PREDICATE_LANE] > 0;
  }

  boolean hasHaving() {
    return nodeCounts[HAVING_LANE] > 0;
  }

  void append(
      int projection, int operator, long operand, int descriptor) {
    int node = nodeCounts[projection]++;
    operators[projection][node] = (byte) operator;
    operands[projection][node] = operand;
    descriptors[projection][node] = descriptor;
  }

  void finish(int projection, int descriptor, int rawColumn) {
    resultDescriptors[projection] = descriptor;
    rawColumns[projection] = rawColumn;
  }

  int count() {
    return count;
  }

  int nodeCount(int projection) {
    return nodeCounts[projection];
  }

  int operator(int projection, int node) {
    return Byte.toUnsignedInt(operators[projection][node]);
  }

  long operand(int projection, int node) {
    return operands[projection][node];
  }

  int descriptor(int projection, int node) {
    return descriptors[projection][node];
  }

  int resultDescriptor(int projection) {
    return resultDescriptors[projection];
  }

  int rawColumn(int projection) {
    return rawColumns[projection];
  }

  boolean computed(int projection) {
    return rawColumns[projection] < 0
        && operator(projection, 0) != SqlScalarExpression.NULL;
  }
}
