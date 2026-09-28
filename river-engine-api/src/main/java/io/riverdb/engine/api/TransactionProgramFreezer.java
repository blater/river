package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Performs final graph admission and publishes immutable derived reference chains. */
final class TransactionProgramFreezer {
  private TransactionProgramFreezer() { }

  static StatusCode freeze(TransactionProgramStorage storage, TransactionProgram owner) {
    if (storage.frozen) return StatusCode.CONFLICT;
    if (storage.currentStep >= 0 || storage.currentExpression >= 0 || storage.stepCount == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = validateSteps(storage);
    if (!status.isOk()) return status;
    status = TransactionProgramControlFlow.validate(owner);
    if (!status.isOk()) return status;
    rebuildReferences(storage);
    return storage.finishFreeze();
  }

  private static StatusCode validateSteps(TransactionProgramStorage storage) {
    for (int step = 0; step < storage.stepCount; step++) {
      if (!validTarget(step, storage.falseTargets[step], storage.stepCount)
          || !validTarget(step, storage.emptyTargets[step], storage.stepCount)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (storage.guards[step] < 0 && storage.falseTargets[step] >= 0) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (storage.actions[step] != TransactionProgramAction.ZERO_OR_ONE
          && storage.emptyTargets[step] >= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (storage.minimumAffectedRows[step] < 0
          || storage.maximumAffectedRows[step] < storage.minimumAffectedRows[step]
          || storage.actions[step] != TransactionProgramAction.COMMAND
              && storage.actions[step] != TransactionProgramAction.ROW_SET
              && (storage.minimumAffectedRows[step] != 0
                  || storage.maximumAffectedRows[step] != Long.MAX_VALUE)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (storage.actions[step] == TransactionProgramAction.ROW_SET
          && (step != storage.stepCount - 1 || storage.captureCounts[step] == 0)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (storage.actions[step] == TransactionProgramAction.ROW_AT) {
        int parameters = storage.parameterCounts[step];
        if (parameters < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
        int ordinal = storage.parameterExpressions[
            storage.firstParameters[step] + parameters - 1];
        int descriptor = storage.expressionDescriptors[ordinal];
        if (descriptor != SqlTypeDescriptor.SMALLINT
            && descriptor != SqlTypeDescriptor.INTEGER
            && descriptor != SqlTypeDescriptor.BIGINT) {
          return StatusCode.DATATYPE_MISMATCH;
        }
      }
    }
    return StatusCode.OK;
  }

  private static void rebuildReferences(TransactionProgramStorage storage) {
    java.util.Arrays.fill(storage.referenceHeads, 0, storage.stepCount, -1);
    for (int node = storage.nodeCount - 1; node >= 0; node--) {
      if (storage.nodeOperators[node] == TransactionScalarOperator.RESULT) {
        int source = storage.nodeFirst[node];
        storage.referenceNext[node] = storage.referenceHeads[source];
        storage.referenceHeads[source] = node;
      }
    }
  }

  private static boolean validTarget(int source, int target, int stepCount) {
    return target == -1 || target > source && target <= stepCount;
  }
}
