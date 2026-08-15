package io.riverdb.sql;

/** Shared bounded postfix arena for INSERT cells and UPDATE assignments. */
final class SqlMutationExpressions {
  static final int MAXIMUM_PROGRAMS = SqlScalarExpression.MAXIMUM_NODES;

  private final byte[] operators = new byte[SqlScalarExpression.MAXIMUM_NODES];
  private final long[] operands = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final byte[] offsets = new byte[MAXIMUM_PROGRAMS];
  private final byte[] counts = new byte[MAXIMUM_PROGRAMS];
  private int programCount;
  private int nodeCount;

  void reset() {
    for (int node = 0; node < nodeCount; node++) {
      operators[node] = 0;
      operands[node] = 0;
      descriptors[node] = 0;
    }
    for (int program = 0; program < programCount; program++) {
      offsets[program] = 0;
      counts[program] = 0;
    }
    programCount = 0;
    nodeCount = 0;
  }

  int append(SqlScalarExpression expression) {
    if (expression == null || !expression.isAvailable()
        || programCount >= counts.length
        || expression.nodeCount() > operators.length - nodeCount) {
      return -1;
    }
    int program = programCount++;
    offsets[program] = (byte) nodeCount;
    counts[program] = (byte) expression.nodeCount();
    for (int node = 0; node < expression.nodeCount(); node++) {
      operators[nodeCount] = (byte) expression.operator(node);
      operands[nodeCount] = expression.operand(node);
      descriptors[nodeCount] = expression.typeDescriptor(node);
      nodeCount++;
    }
    return program;
  }

  int programCount() { return programCount; }
  int nodeCount(int program) { return valid(program) ? Byte.toUnsignedInt(counts[program]) : 0; }
  int operator(int program, int node) {
    int slot = slot(program, node);
    return slot < 0 ? 0 : Byte.toUnsignedInt(operators[slot]);
  }
  long operand(int program, int node) {
    int slot = slot(program, node);
    return slot < 0 ? 0 : operands[slot];
  }
  int descriptor(int program, int node) {
    int slot = slot(program, node);
    return slot < 0 ? 0 : descriptors[slot];
  }

  private int slot(int program, int node) {
    return valid(program) && node >= 0 && node < Byte.toUnsignedInt(counts[program])
        ? Byte.toUnsignedInt(offsets[program]) + node : -1;
  }

  private boolean valid(int program) {
    return program >= 0 && program < programCount;
  }
}
