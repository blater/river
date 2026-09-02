package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionScalarOperator;
import java.nio.ByteBuffer;

/** Rebuilds one wire expression through the public program builder. */
final class ProtocolProgramGraphExpressionDecoder {
  private ProtocolProgramGraphExpressionDecoder() { }

  static int decode(
      ByteBuffer source, int offset, int end, TransactionProgram program,
      boolean guard, int falseTarget, int expression) {
    if (offset > end - ProtocolProgramGraphCodec.EXPRESSION_BYTES) return -1;
    int count = source.getInt(offset);
    int descriptor = source.getInt(offset + 4);
    if (count <= 0 || offset + ProtocolProgramGraphCodec.EXPRESSION_BYTES
        > end - count * (long) ProtocolProgramGraphCodec.NODE_BYTES) return -1;
    StatusCode status = guard ? program.beginGuard(falseTarget) : program.beginParameter();
    if (!status.isOk()) return -1;
    int input = offset + ProtocolProgramGraphCodec.EXPRESSION_BYTES;
    for (int index = 0; index < count; index++) {
      status = appendNode(
          program, source.getInt(input), source.getInt(input + 4),
          source.getInt(input + 8), source.getInt(input + 12));
      if (!status.isOk()) return -1;
      input += ProtocolProgramGraphCodec.NODE_BYTES;
    }
    status = program.endExpression();
    return status.isOk() && program.expressionDescriptor(expression) == descriptor ? input : -1;
  }

  private static StatusCode appendNode(
      TransactionProgram program, int operator, int first, int second, int descriptor) {
    return switch (operator) {
      case TransactionScalarOperator.ARGUMENT -> first >= 0 && second == 0
          ? program.argument(first, descriptor) : StatusCode.INVALID_EXTERNAL_INPUT;
      case TransactionScalarOperator.RESULT -> first >= 0 && second >= 0
          ? program.priorResult(first, second, descriptor) : StatusCode.INVALID_EXTERNAL_INPUT;
      case TransactionScalarOperator.NULL -> first == 0 && second == 0
          ? program.nullValue(descriptor) : StatusCode.INVALID_EXTERNAL_INPUT;
      default -> first == 0 && second == 0
          ? program.operator(operator, descriptor) : StatusCode.INVALID_EXTERNAL_INPUT;
    };
  }
}
