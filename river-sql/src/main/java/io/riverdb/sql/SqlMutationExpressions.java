package io.riverdb.sql;

/** Shared bounded postfix arena for INSERT cells and UPDATE assignments. */
final class SqlMutationExpressions {
  static final int MAXIMUM_PROGRAMS = SqlScalarExpression.MAXIMUM_NODES;

  byte[] operators = new byte[16];
  long[] operandHighs = new long[16];
  long[] operands = new long[16];
  int[] descriptors = new int[16];
  int[] offsets = new int[16];
  int[] counts = new int[16];
  private int programCount;
  int nodeCount;

  void reset() {
    for (int node = 0; node < nodeCount; node++) {
      operators[node] = 0;
      operandHighs[node] = 0;
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
        || programCount >= MAXIMUM_PROGRAMS
        || expression.nodeCount() > SqlScalarExpression.MAXIMUM_NODES - nodeCount
        || !SqlMutationCapacity.ensure(
            this, programCount + 1, nodeCount + expression.nodeCount())) {
      return -1;
    }
    int program = programCount++;
    offsets[program] = nodeCount;
    counts[program] = expression.nodeCount();
    for (int node = 0; node < expression.nodeCount(); node++) {
      operators[nodeCount] = (byte) expression.operator(node);
      operandHighs[nodeCount] = expression.operandHigh(node);
      operands[nodeCount] = expression.operand(node);
      descriptors[nodeCount] = expression.typeDescriptor(node);
      nodeCount++;
    }
    return program;
  }

  int programCount() { return programCount; }
  int nodeCount(int program) { return valid(program) ? counts[program] : 0; }
  int operator(int program, int node) {
    int slot = slot(program, node);
    return slot < 0 ? 0 : Byte.toUnsignedInt(operators[slot]);
  }
  long operand(int program, int node) {
    int slot = slot(program, node);
    return slot < 0 ? 0 : operands[slot];
  }
  long operandHigh(int program, int node) {
    int slot = slot(program, node);
    return slot < 0 ? 0 : operandHighs[slot];
  }
  int descriptor(int program, int node) {
    int slot = slot(program, node);
    return slot < 0 ? 0 : descriptors[slot];
  }

  private int slot(int program, int node) {
    return valid(program) && node >= 0 && node < counts[program]
        ? offsets[program] + node : -1;
  }

  private boolean valid(int program) {
    return program >= 0 && program < programCount;
  }
}
