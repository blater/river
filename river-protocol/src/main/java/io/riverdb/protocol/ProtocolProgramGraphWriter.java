package io.riverdb.protocol;

import io.riverdb.engine.api.TransactionProgram;
import java.nio.ByteBuffer;

/** Writes a validated frozen graph directly into its reserved frame payload. */
final class ProtocolProgramGraphWriter {
  private ProtocolProgramGraphWriter() { }

  static void write(ByteBuffer target, int start, TransactionProgram program, int expressions) {
    int steps = program.stepCount();
    int output = start;
    target.putInt(output, ProtocolProgramGraphCodec.FORMAT);
    target.putInt(output + 4, steps);
    target.putInt(output + 8, expressions);
    target.putInt(output + 12, ProtocolProgramGraphShape.nodes(program));
    target.putInt(output + 16, ProtocolProgramGraphShape.captures(program));
    target.putInt(output + 20, program.requiredArgumentSlots());
    target.putInt(output + 24, 0);
    target.putInt(output + 28, 0);
    output += ProtocolProgramGraphCodec.HEADER_BYTES;
    int expression = 0;
    for (int step = 0; step < steps; step++) {
      int firstCapture = program.firstCapture(step);
      int captureCount = program.captureCount(step);
      target.putLong(output, program.preparedHandle(step));
      target.putInt(output + 8, program.action(step));
      target.putInt(output + 12, program.firstParameter(step));
      target.putInt(output + 16, program.parameterCount(step));
      target.putInt(output + 20, program.guardExpression(step));
      target.putInt(output + 24, program.falseTarget(step));
      target.putInt(output + 28, program.emptyTarget(step));
      target.putInt(output + 32, firstCapture);
      target.putInt(output + 36, captureCount);
      target.putLong(output + 40, program.minimumAffectedRows(step));
      target.putLong(output + 48, program.maximumAffectedRows(step));
      output += ProtocolProgramGraphCodec.STEP_BYTES;
      for (int parameter = 0; parameter < program.parameterCount(step); parameter++) {
        output = expression(target, output, program, expression++);
      }
      if (program.guardExpression(step) >= 0) {
        output = expression(target, output, program, expression++);
      }
      for (int capture = 0; capture < captureCount; capture++) {
        target.putInt(output, program.captureColumnAt(firstCapture + capture));
        output += Integer.BYTES;
      }
    }
  }

  private static int expression(
      ByteBuffer target, int output, TransactionProgram program, int expression) {
    int count = program.expressionNodeCount(expression);
    target.putInt(output, count);
    target.putInt(output + 4, program.expressionDescriptor(expression));
    output += ProtocolProgramGraphCodec.EXPRESSION_BYTES;
    int first = program.expressionFirstNode(expression);
    for (int index = 0; index < count; index++) {
      int node = first + index;
      target.putInt(output, program.nodeOperator(node));
      target.putInt(output + 4, program.nodeFirst(node));
      target.putInt(output + 8, program.nodeSecond(node));
      target.putInt(output + 12, program.nodeDescriptor(node));
      output += ProtocolProgramGraphCodec.NODE_BYTES;
    }
    return output;
  }
}
