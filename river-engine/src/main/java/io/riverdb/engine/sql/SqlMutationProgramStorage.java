package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Actual-count mutation programs backed by one reusable flat node arena. */
final class SqlMutationProgramStorage {
  /** Keeps the flat primitive node arena below the 8 MiB session-shape budget. */
  static final int MAXIMUM_TOTAL_NODES = 524_288;
  private static final long PROGRAM_BYTES = 12;
  private static final long NODE_BYTES = 21;
  private final SqlSessionShapeBudget budget;
  private byte[] operators = new byte[0];
  private long[] operandHighs = new long[0];
  private long[] operands = new long[0];
  private int[] descriptors = new int[0];
  private int[] offsets = new int[0];
  private int[] counts = new int[0];
  private int[] resultDescriptors = new int[0];
  private int count;
  private int nodeCount;
  private StatusCode status = StatusCode.OK;

  SqlMutationProgramStorage() {
    this(new SqlSessionShapeBudget(null));
  }

  SqlMutationProgramStorage(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode reserve(int programs) {
    if (programs < 0 || programs > SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (programs <= offsets.length) return StatusCode.OK;
    int capacity = grow(offsets.length, programs, SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS);
    long charged = (capacity - offsets.length) * PROGRAM_BYTES;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      int[] nextOffsets = new int[capacity];
      int[] nextCounts = new int[capacity];
      int[] nextResults = new int[capacity];
      System.arraycopy(offsets, 0, nextOffsets, 0, count);
      System.arraycopy(counts, 0, nextCounts, 0, count);
      System.arraycopy(resultDescriptors, 0, nextResults, 0, count);
      offsets = nextOffsets;
      counts = nextCounts;
      resultDescriptors = nextResults;
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
  }

  void beginProgram(int program) {
    if (!status.isOk() || program < 0 || program >= count) return;
    offsets[program] = nodeCount;
    counts[program] = 0;
  }

  void append(int program, int operator, long operand, int descriptor) {
    append(program, operator, operand >> 63, operand, descriptor);
  }

  void append(
      int program, int operator, long operandHigh, long operand, int descriptor) {
    if (!status.isOk()
        || program < 0
        || program >= count
        || counts[program] >= SqlShapeLimits.MAX_EXPRESSION_NODES
        || !reserveNodes(nodeCount + 1)) {
      status = StatusCode.RESOURCE_EXHAUSTED;
      return;
    }
    operators[nodeCount] = (byte) operator;
    operandHighs[nodeCount] = operandHigh;
    operands[nodeCount] = operand;
    descriptors[nodeCount++] = descriptor;
    counts[program]++;
  }

  void finish(int program, int descriptor) {
    if (status.isOk()) resultDescriptors[program] = descriptor;
  }

  void reset() {
    for (int node = 0; node < nodeCount; node++) {
      operators[node] = 0;
      operandHighs[node] = 0;
      operands[node] = 0;
      descriptors[node] = 0;
    }
    for (int program = 0; program < count; program++) {
      offsets[program] = 0;
      counts[program] = 0;
      resultDescriptors[program] = 0;
    }
    count = 0;
    nodeCount = 0;
    status = StatusCode.OK;
  }

  StatusCode status() { return status; }
  int count() { return count; }
  int nodeCount(int program) { return counts[program]; }
  int operator(int program, int node) { return Byte.toUnsignedInt(operators[slot(program, node)]); }
  long operand(int program, int node) { return operands[slot(program, node)]; }
  long operandHigh(int program, int node) { return operandHighs[slot(program, node)]; }
  int descriptor(int program, int node) { return descriptors[slot(program, node)]; }
  int resultDescriptor(int program) { return resultDescriptors[program]; }
  private int slot(int program, int node) { return offsets[program] + node; }

  private boolean reserveNodes(int required) {
    if (required > MAXIMUM_TOTAL_NODES) {
      status = StatusCode.RESOURCE_EXHAUSTED;
      return false;
    }
    if (required <= operators.length) return true;
    int capacity = grow(operators.length, required, MAXIMUM_TOTAL_NODES);
    long charged = (capacity - operators.length) * NODE_BYTES;
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
      System.arraycopy(operators, 0, nextOperators, 0, nodeCount);
      System.arraycopy(operandHighs, 0, nextOperandHighs, 0, nodeCount);
      System.arraycopy(operands, 0, nextOperands, 0, nodeCount);
      System.arraycopy(descriptors, 0, nextDescriptors, 0, nodeCount);
      operators = nextOperators;
      operandHighs = nextOperandHighs;
      operands = nextOperands;
      descriptors = nextDescriptors;
      return true;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      status = StatusCode.RESOURCE_EXHAUSTED;
      return false;
    }
  }

  private static int grow(int current, int required, int maximum) {
    return BoundedArrayGrowth.capacity(current, required, maximum, 8);
  }
}
