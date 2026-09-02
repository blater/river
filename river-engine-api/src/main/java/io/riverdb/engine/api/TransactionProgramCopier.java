package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Replays a frozen canonical graph through the validated builder API. */
final class TransactionProgramCopier {
  private TransactionProgramCopier() { }

  static StatusCode copy(TransactionProgramStorage source, TransactionProgramBuilder target) {
    target.reset();
    StatusCode status = StatusCode.OK;
    for (int step = 0; status.isOk() && step < source.stepCount; step++) {
      status = target.beginStep(source.handles[step], source.actions[step]);
      if (status.isOk() && source.actions[step] == TransactionProgramAction.COMMAND) {
        status = target.requireAffectedRows(
            source.minimumAffectedRows[step], source.maximumAffectedRows[step]);
      }
      status = copyParameters(source, target, step, status);
      status = copyGuard(source, target, step, status);
      status = copyCaptures(source, target, step, status);
      if (status.isOk() && source.emptyTargets[step] >= 0) {
        status = target.skipOnEmpty(source.emptyTargets[step]);
      }
      if (status.isOk()) status = target.endStep();
    }
    if (status.isOk()) status = target.freeze();
    if (!status.isOk()) target.reset();
    return status;
  }

  private static StatusCode copyParameters(
      TransactionProgramStorage source, TransactionProgramBuilder target, int step, StatusCode status) {
    int first = source.firstParameters[step];
    int end = first + source.parameterCounts[step];
    for (int parameter = first; status.isOk() && parameter < end; parameter++) {
      status = target.beginParameter();
      if (status.isOk()) status = copyExpression(source, target, source.parameterExpressions[parameter]);
    }
    return status;
  }

  private static StatusCode copyGuard(
      TransactionProgramStorage source, TransactionProgramBuilder target, int step, StatusCode status) {
    int guard = source.guards[step];
    if (status.isOk() && guard >= 0) {
      status = target.beginGuard(source.falseTargets[step]);
      if (status.isOk()) status = copyExpression(source, target, guard);
    }
    return status;
  }

  private static StatusCode copyCaptures(
      TransactionProgramStorage source, TransactionProgramBuilder target, int step, StatusCode status) {
    int first = source.firstCaptures[step];
    int end = first + source.captureCounts[step];
    for (int capture = first; status.isOk() && capture < end; capture++) {
      status = target.captureColumn(source.captureColumns[capture]);
    }
    return status;
  }

  private static StatusCode copyExpression(
      TransactionProgramStorage source, TransactionProgramBuilder target, int expression) {
    int first = source.expressionFirstNodes[expression];
    int end = first + source.expressionNodeCounts[expression];
    for (int node = first; node < end; node++) {
      int operator = source.nodeOperators[node];
      StatusCode status = switch (operator) {
        case TransactionScalarOperator.ARGUMENT ->
            target.argument(source.nodeFirst[node], source.nodeDescriptors[node]);
        case TransactionScalarOperator.RESULT ->
            target.priorResult(source.nodeFirst[node], source.nodeSecond[node], source.nodeDescriptors[node]);
        case TransactionScalarOperator.NULL -> target.nullValue(source.nodeDescriptors[node]);
        default -> target.operator(operator, source.nodeDescriptors[node]);
      };
      if (!status.isOk()) return status;
    }
    return target.endExpression();
  }
}
