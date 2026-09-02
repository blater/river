package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import java.nio.ByteBuffer;

/** Decodes one graph step and rebuilds it with the program builder. */
final class ProtocolProgramGraphStepDecoder {
  private ProtocolProgramGraphStepDecoder() { }

  static int decode(
      ByteBuffer source, int input, int end, TransactionProgram program, int step,
      int expressions, int parameter, int expression, int capture) {
    if (input > end - ProtocolProgramGraphCodec.STEP_BYTES) return -1;
    long handle = source.getLong(input);
    int action = source.getInt(input + 8);
    int firstParameter = source.getInt(input + 12);
    int parameterCount = source.getInt(input + 16);
    int guard = source.getInt(input + 20);
    int falseTarget = source.getInt(input + 24);
    int emptyTarget = source.getInt(input + 28);
    int firstCapture = source.getInt(input + 32);
    int captureCount = source.getInt(input + 36);
    long minimumAffected = source.getLong(input + 40);
    long maximumAffected = source.getLong(input + 48);
    if (parameterCount < 0 || captureCount < 0
        || firstParameter != parameter
        || firstCapture != capture || guard < -1
        || guard >= expressions && guard != -1
        || guard < 0 && falseTarget != -1
        || emptyTarget < -1) return -1;
    StatusCode status = program.beginStep(handle, action);
    if (!status.isOk()) return -1;
    if (action == TransactionProgramAction.COMMAND) {
      status = program.requireAffectedRows(minimumAffected, maximumAffected);
      if (!status.isOk()) return -1;
    } else if (minimumAffected != 0 || maximumAffected != Long.MAX_VALUE) {
      return -1;
    }
    int next = input + ProtocolProgramGraphCodec.STEP_BYTES;
    for (int parameterIndex = 0; parameterIndex < parameterCount; parameterIndex++) {
      next = ProtocolProgramGraphExpressionDecoder.decode(
          source, next, end, program, false, -1, expression++);
      if (next < 0) return -1;
    }
    if (guard >= 0) {
      if (guard != expression) return -1;
      next = ProtocolProgramGraphExpressionDecoder.decode(
          source, next, end, program, true, falseTarget, expression++);
      if (next < 0) return -1;
    }
    if (emptyTarget >= 0) {
      status = program.skipOnEmpty(emptyTarget);
      if (!status.isOk()) return -1;
    }
    if ((long) captureCount * Integer.BYTES > end - next) return -1;
    for (int index = 0; index < captureCount; index++) {
      status = program.captureColumn(source.getInt(next));
      if (!status.isOk()) return -1;
      next += Integer.BYTES;
    }
    status = program.endStep();
    return status.isOk() ? next : -1;
  }

}
