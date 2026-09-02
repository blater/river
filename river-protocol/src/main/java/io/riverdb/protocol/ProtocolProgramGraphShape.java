package io.riverdb.protocol;

import io.riverdb.engine.api.TransactionProgram;

/** Computes the canonical graph payload shape without materialising it. */
final class ProtocolProgramGraphShape {
  private ProtocolProgramGraphShape() { }

  static int expressions(TransactionProgram program) {
    int count = 0;
    for (int step = 0; step < program.stepCount(); step++) {
      count += program.parameterCount(step);
      if (program.guardExpression(step) >= 0) count++;
    }
    return count;
  }

  static int captures(TransactionProgram program) {
    int count = 0;
    for (int step = 0; step < program.stepCount(); step++) {
      count += program.captureCount(step);
    }
    return count;
  }

  static long payloadBytes(int steps, int expressions, int nodes, int captures) {
    return ProtocolProgramGraphCodec.HEADER_BYTES
        + (long) steps * ProtocolProgramGraphCodec.STEP_BYTES
        + (long) expressions * ProtocolProgramGraphCodec.EXPRESSION_BYTES
        + (long) nodes * ProtocolProgramGraphCodec.NODE_BYTES
        + (long) captures * Integer.BYTES;
  }

  static int nodes(TransactionProgram program) {
    int count = 0;
    for (int expression = 0; expression < expressions(program); expression++) {
      count += program.expressionNodeCount(expression);
    }
    return count;
  }
}
