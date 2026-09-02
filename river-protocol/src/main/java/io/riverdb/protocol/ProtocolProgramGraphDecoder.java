package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import java.nio.ByteBuffer;

/** Strictly validates and rebuilds a PREPARE_PROGRAM graph payload. */
final class ProtocolProgramGraphDecoder {
  private ProtocolProgramGraphDecoder() { }

  static StatusCode decode(
      ByteBuffer source, int offset, int end, TransactionProgram program) {
    if (source == null || program == null || offset < 0 || end < offset
        || end > source.limit() || end - offset < ProtocolProgramGraphCodec.HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int format = source.getInt(offset);
    int steps = source.getInt(offset + 4);
    int expressions = source.getInt(offset + 8);
    int nodes = source.getInt(offset + 12);
    int captures = source.getInt(offset + 16);
    int arguments = source.getInt(offset + 20);
    if (format != ProtocolProgramGraphCodec.FORMAT || source.getInt(offset + 24) != 0
        || source.getInt(offset + 28) != 0 || steps <= 0 || expressions < 0
        || nodes < 0 || captures < 0 || arguments < 0
        || !fitsPayload(steps, expressions, nodes, captures, end - offset)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    program.reset();
    int input = offset + ProtocolProgramGraphCodec.HEADER_BYTES;
    int parameter = 0;
    int expression = 0;
    int capture = 0;
    for (int step = 0; step < steps; step++) {
      int next = ProtocolProgramGraphStepDecoder.decode(
          source, input, end, program, step, expressions,
          parameter, expression, capture);
      if (next < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      int parameterCount = source.getInt(input + 16);
      int guard = source.getInt(input + 20);
      int captureCount = source.getInt(input + 36);
      input = next;
      parameter += parameterCount;
      expression += parameterCount + (guard >= 0 ? 1 : 0);
      capture += captureCount;
    }
    return expression == expressions && capture == captures
        && ProtocolProgramGraphShape.nodes(program) == nodes
        && arguments == program.requiredArgumentSlots() && input == end
        ? program.freeze() : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean fitsPayload(
      int steps, int expressions, int nodes, int captures, int bytes) {
    long required = ProtocolProgramGraphShape.payloadBytes(steps, expressions, nodes, captures);
    return required <= bytes;
  }
}
