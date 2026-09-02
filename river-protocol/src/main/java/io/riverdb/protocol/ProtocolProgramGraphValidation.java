package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgram;
import io.riverdb.engine.api.TransactionProgramAction;
import io.riverdb.engine.api.TransactionScalarOperator;

/** Checks the derived layout before a frozen graph is published on the wire. */
final class ProtocolProgramGraphValidation {
  private ProtocolProgramGraphValidation() { }

  static boolean valid(
      TransactionProgram program, int expressions, int captures, int nodes, int arguments) {
    int parameter = 0;
    int expression = 0;
    int capture = 0;
    int node = 0;
    for (int step = 0; step < program.stepCount(); step++) {
      if (!validStep(program, step, parameter, capture)) return false;
      int count = program.parameterCount(step);
      for (int index = 0; index < count; index++) {
        if (program.parameterExpression(parameter++) != expression
            || !validExpression(program, expression, node)) return false;
        node += program.expressionNodeCount(expression++);
      }
      int guard = program.guardExpression(step);
      if (guard >= 0) {
        if (guard != expression || !validExpression(program, expression, node)) return false;
        node += program.expressionNodeCount(expression++);
      } else if (guard != -1 || program.falseTarget(step) != -1) {
        return false;
      }
      capture += program.captureCount(step);
    }
    return parameter == countParameters(program)
        && expression == expressions && node == nodes && capture == captures
        && arguments == program.requiredArgumentSlots();
  }

  private static boolean validStep(
      TransactionProgram program, int step, int parameter, int capture) {
    int action = program.action(step);
    if (program.preparedHandle(step) <= 0 || !TransactionProgramAction.isValid(action)
        || program.firstParameter(step) != parameter
        || program.firstCapture(step) != capture
        || program.parameterCount(step) < 0 || program.captureCount(step) < 0) return false;
    if (program.minimumAffectedRows(step) < 0
        || program.maximumAffectedRows(step) < program.minimumAffectedRows(step)
        || action != TransactionProgramAction.COMMAND
            && (program.minimumAffectedRows(step) != 0
                || program.maximumAffectedRows(step) != Long.MAX_VALUE)) return false;
    int empty = program.emptyTarget(step);
    return (empty < 0 && empty == -1)
        || action == TransactionProgramAction.ZERO_OR_ONE && empty > step
            && empty <= program.stepCount();
  }

  private static boolean validExpression(TransactionProgram program, int expression, int node) {
    int first = program.expressionFirstNode(expression);
    int count = program.expressionNodeCount(expression);
    if (first != node || count <= 0
        || !SqlTypeDescriptor.isValid(program.expressionDescriptor(expression))) return false;
    for (int index = 0; index < count; index++) {
      int current = first + index;
      int operator = program.nodeOperator(current);
      if (!TransactionScalarOperator.isValid(operator)
          || !SqlTypeDescriptor.isValid(program.nodeDescriptor(current))) return false;
    }
    return true;
  }

  private static int countParameters(TransactionProgram program) {
    int count = 0;
    for (int step = 0; step < program.stepCount(); step++) count += program.parameterCount(step);
    return count;
  }
}
