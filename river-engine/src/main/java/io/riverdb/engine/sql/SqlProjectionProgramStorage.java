package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlScalarExpression;

/** Actual-count projection program arrays; growth occurs only while binding. */
final class SqlProjectionProgramStorage {
  private static final long PROGRAM_BYTES = 44;
  private static final long NODE_BYTES = 25;
  private final SqlSessionShapeBudget budget;
  private byte[][] operators = new byte[0][];
  private long[][] operandHighs = new long[0][];
  private long[][] operands = new long[0][];
  private int[][] descriptors = new int[0][];
  private int[][] scopes = new int[0][];
  private int[] nodeCounts = new int[0];
  private int[] resultDescriptors = new int[0];
  private int[] rawColumns = new int[0];
  private int count;
  private StatusCode status = StatusCode.OK;

  SqlProjectionProgramStorage() {
    this(new SqlSessionShapeBudget(null));
  }

  SqlProjectionProgramStorage(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode reserve(int programs) {
    if (programs < 0 || programs > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (programs <= nodeCounts.length) return StatusCode.OK;
    int capacity = grow(nodeCounts.length, programs, SqlShapeLimits.MAX_RESULT_COLUMNS);
    long charged = (capacity - nodeCounts.length) * PROGRAM_BYTES;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      byte[][] nextOperators = new byte[capacity][];
      long[][] nextOperandHighs = new long[capacity][];
      long[][] nextOperands = new long[capacity][];
      int[][] nextDescriptors = new int[capacity][];
      int[][] nextScopes = new int[capacity][];
      int[] nextCounts = new int[capacity];
      int[] nextResults = new int[capacity];
      int[] nextRaw = new int[capacity];
      copy(nextOperators, nextOperandHighs, nextOperands, nextDescriptors, nextScopes,
          nextCounts, nextResults, nextRaw);
      operators = nextOperators;
      operandHighs = nextOperandHighs;
      operands = nextOperands;
      descriptors = nextDescriptors;
      scopes = nextScopes;
      nodeCounts = nextCounts;
      resultDescriptors = nextResults;
      rawColumns = nextRaw;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void begin(int programs) {
    StatusCode reserved = reserve(programs);
    if (!reserved.isOk()) {
      status = reserved;
      return;
    }
    reset();
    count = programs;
    for (int index = 0; index < count; index++) rawColumns[index] = -1;
  }

  void reset() {
    for (int program = 0; program < count; program++) {
      for (int node = 0; node < nodeCounts[program]; node++) {
        operators[program][node] = 0;
        operandHighs[program][node] = 0;
        operands[program][node] = 0;
        descriptors[program][node] = 0;
        scopes[program][node] = 0;
      }
      nodeCounts[program] = 0;
      resultDescriptors[program] = 0;
      rawColumns[program] = -1;
    }
    count = 0;
    status = StatusCode.OK;
  }

  void append(int program, int operator, long operand, int descriptor, int scope) {
    append(program, operator, operand >> 63, operand, descriptor, scope);
  }

  void append(
      int program,
      int operator,
      long operandHigh,
      long operand,
      int descriptor,
      int scope) {
    if (!status.isOk() || !reserveNodes(program, nodeCounts[program] + 1)) return;
    int node = nodeCounts[program]++;
    operators[program][node] = (byte) operator;
    operandHighs[program][node] = operandHigh;
    operands[program][node] = operand;
    descriptors[program][node] = descriptor;
    scopes[program][node] = scope;
  }

  void finish(int program, int descriptor, int rawColumn) {
    if (!status.isOk()) return;
    resultDescriptors[program] = descriptor;
    rawColumns[program] = rawColumn;
  }

  void resolveNull(int program, int descriptor) {
    if (nodeCounts[program] == 1 && operators[program][0] == SqlScalarExpression.NULL) {
      descriptors[program][0] = descriptor;
      resultDescriptors[program] = descriptor;
    }
  }

  boolean referencesScope(int program, int scope) {
    for (int node = 0; node < nodeCounts[program]; node++) {
      if (operators[program][node] == SqlScalarExpression.COLUMN
          && scopes[program][node] == scope) return true;
    }
    return false;
  }

  StatusCode status() { return status; }
  int count() { return count; }
  int nodeCount(int program) { return nodeCounts[program]; }
  int operator(int program, int node) { return Byte.toUnsignedInt(operators[program][node]); }
  long operand(int program, int node) { return operands[program][node]; }
  long operandHigh(int program, int node) { return operandHighs[program][node]; }
  int descriptor(int program, int node) { return descriptors[program][node]; }
  int scope(int program, int node) { return scopes[program][node]; }
  int resultDescriptor(int program) { return resultDescriptors[program]; }
  int rawColumn(int program) { return rawColumns[program]; }
  boolean computed(int program) {
    return rawColumns[program] < 0 && operator(program, 0) != SqlScalarExpression.NULL;
  }

  private boolean reserveNodes(int program, int required) {
    if (program < 0 || program >= count || required > SqlShapeLimits.MAX_EXPRESSION_NODES) {
      status = StatusCode.RESOURCE_EXHAUSTED;
      return false;
    }
    int current = operators[program] == null ? 0 : operators[program].length;
    if (required <= current) return true;
    int capacity = grow(current, required, SqlShapeLimits.MAX_EXPRESSION_NODES);
    long charged = (capacity - current) * NODE_BYTES;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) {
      status = admitted;
      return false;
    }
    try {
      byte[] nextOperators = new byte[capacity];
      long[] nextOperandHighs = new long[capacity];
      long[] nextOperands = new long[capacity];
      int[] nextDescriptors = new int[capacity];
      int[] nextScopes = new int[capacity];
      if (current != 0) {
        System.arraycopy(operators[program], 0, nextOperators, 0, nodeCounts[program]);
        System.arraycopy(
            operandHighs[program], 0, nextOperandHighs, 0, nodeCounts[program]);
        System.arraycopy(operands[program], 0, nextOperands, 0, nodeCounts[program]);
        System.arraycopy(descriptors[program], 0, nextDescriptors, 0, nodeCounts[program]);
        System.arraycopy(scopes[program], 0, nextScopes, 0, nodeCounts[program]);
      }
      operators[program] = nextOperators;
      operandHighs[program] = nextOperandHighs;
      operands[program] = nextOperands;
      descriptors[program] = nextDescriptors;
      scopes[program] = nextScopes;
      return true;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      status = StatusCode.RESOURCE_EXHAUSTED;
      return false;
    }
  }

  private void copy(
      byte[][] nextOperators,
      long[][] nextOperandHighs,
      long[][] nextOperands,
      int[][] nextDescriptors,
      int[][] nextScopes,
      int[] nextCounts,
      int[] nextResults,
      int[] nextRaw) {
    System.arraycopy(operators, 0, nextOperators, 0, count);
    System.arraycopy(operandHighs, 0, nextOperandHighs, 0, count);
    System.arraycopy(operands, 0, nextOperands, 0, count);
    System.arraycopy(descriptors, 0, nextDescriptors, 0, count);
    System.arraycopy(scopes, 0, nextScopes, 0, count);
    System.arraycopy(nodeCounts, 0, nextCounts, 0, count);
    System.arraycopy(resultDescriptors, 0, nextResults, 0, count);
    System.arraycopy(rawColumns, 0, nextRaw, 0, count);
  }

  private static int grow(int current, int required, int maximum) {
    return BoundedArrayGrowth.capacity(current, required, maximum, 8);
  }
}
