package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Validates the forward DAG and definite availability of prior single-row values. */
final class TransactionProgramControlFlow {
  private TransactionProgramControlFlow() { }

  static StatusCode validate(TransactionProgram program) {
    int steps = program.stepCount();
    long edgeCount = edgeCount(program);
    if (edgeCount < 0 || edgeCount > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    long bytes = TransactionProgramValidationWorkspace.retainedBytes(
        steps, (int) edgeCount, program.nodeCount());
    if (bytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = program.reserveValidation(bytes);
    if (!status.isOk()) return status;
    StatusCode validation;
    try {
      TransactionProgramValidationWorkspace workspace;
      try {
        workspace = TransactionProgramValidationWorkspace.create(
            steps, (int) edgeCount, program.nodeCount());
      } catch (OutOfMemoryError failure) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      validation = workspace.build(program);
    } finally {
      status = program.releaseValidation();
    }
    return validation.isOk() && !status.isOk() ? status : validation;
  }

  private static long edgeCount(TransactionProgram program) {
    long edges = 0;
    for (int step = 0; step < program.stepCount(); step++) {
      if (step + 1 < program.stepCount()) edges++;
      if (program.falseTarget(step) >= 0 && program.falseTarget(step) < program.stepCount()) edges++;
      if (program.emptyTarget(step) >= 0 && program.emptyTarget(step) < program.stepCount()) edges++;
    }
    return edges;
  }
}
