package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Stateful editor for one transaction-program graph. */
final class TransactionProgramBuilder {
  private final TransactionProgramStorage s;

  TransactionProgramBuilder(TransactionProgramStorage storage) {
    s = storage;
  }

  StatusCode beginStep(long handle, int action) {
    if (s.frozen || s.currentStep >= 0 || handle <= 0 || !TransactionProgramAction.isValid(action)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = s.ensureSteps(s.stepCount + 1);
    if (!status.isOk()) return status;
    s.currentStep = s.stepCount;
    s.handles[s.currentStep] = handle;
    s.minimumAffectedRows[s.currentStep] = 0;
    s.maximumAffectedRows[s.currentStep] = Long.MAX_VALUE;
    s.actions[s.currentStep] = action;
    s.firstParameters[s.currentStep] = s.parameterCount;
    s.guards[s.currentStep] = -1;
    s.falseTargets[s.currentStep] = -1;
    s.emptyTargets[s.currentStep] = -1;
    s.firstCaptures[s.currentStep] = s.captureCount;
    return StatusCode.OK;
  }

  StatusCode requireAffectedRows(long minimum, long maximum) {
    if (s.frozen || s.currentStep < 0 || s.currentExpression >= 0
        || s.actions[s.currentStep] != TransactionProgramAction.COMMAND
        || minimum < 0 || maximum < minimum) return StatusCode.INVALID_EXTERNAL_INPUT;
    s.minimumAffectedRows[s.currentStep] = minimum;
    s.maximumAffectedRows[s.currentStep] = maximum;
    return StatusCode.OK;
  }

  StatusCode beginParameter() {
    return beginExpression(false, -1);
  }

  StatusCode beginGuard(int falseTarget) {
    if (falseTarget < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = beginExpression(true, falseTarget);
    if (status.isOk()) s.falseTargets[s.currentStep] = falseTarget;
    return status;
  }

  StatusCode argument(int slot, int descriptor) {
    if (!expressionOpen() || slot < 0 || slot == Integer.MAX_VALUE
        || !SqlTypeDescriptor.isValid(descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = appendNode(TransactionScalarOperator.ARGUMENT, slot, 0, descriptor);
    if (status.isOk() && slot >= s.maximumArgumentSlot) s.maximumArgumentSlot = slot + 1;
    return status;
  }

  StatusCode priorResult(int step, int column, int descriptor) {
    if (!expressionOpen() || step < 0 || step >= s.currentStep || column < 0
        || !SqlTypeDescriptor.isValid(descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    return appendNode(TransactionScalarOperator.RESULT, step, column, descriptor);
  }

  StatusCode nullValue(int descriptor) {
    return !expressionOpen() || !SqlTypeDescriptor.isValid(descriptor)
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : appendNode(TransactionScalarOperator.NULL, 0, 0, descriptor);
  }

  StatusCode operator(int operator, int targetDescriptor) {
    if (!expressionOpen() || TransactionScalarOperator.operands(operator) <= 0
        || !SqlTypeDescriptor.isValid(targetDescriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int operands = TransactionScalarOperator.operands(operator);
    if (s.currentStackDepth < operands) return StatusCode.INVALID_EXTERNAL_INPUT;
    int first = s.typeStack[s.currentStackDepth - operands];
    int second = operands >= 2 ? s.typeStack[s.currentStackDepth - operands + 1] : 0;
    int third = operands == 3 ? s.typeStack[s.currentStackDepth - 1] : 0;
    if (!TransactionProgramTypeRules.accepts(operator, first, second, third, targetDescriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    StatusCode status = appendRawNode(operator, 0, 0, targetDescriptor);
    if (!status.isOk()) return status;
    s.currentStackDepth -= operands - 1;
    s.typeStack[s.currentStackDepth - 1] = targetDescriptor;
    return StatusCode.OK;
  }

  StatusCode endExpression() {
    if (!expressionOpen() || s.currentStackDepth != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int descriptor = s.typeStack[0];
    if (s.currentGuard && descriptor != SqlTypeDescriptor.BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    s.expressionNodeCounts[s.currentExpression] = s.nodeCount - s.expressionFirstNodes[s.currentExpression];
    s.expressionDescriptors[s.currentExpression] = descriptor;
    if (s.currentGuard) s.guards[s.currentStep] = s.currentExpression;
    else s.parameterExpressions[s.parameterCount++] = s.currentExpression;
    s.expressionCount++;
    s.currentExpression = -1;
    s.currentStackDepth = 0;
    s.currentGuard = false;
    return StatusCode.OK;
  }

  StatusCode skipOnEmpty(int targetStep) {
    if (s.frozen || s.currentStep < 0 || s.currentExpression >= 0 || targetStep < 0
        || s.actions[s.currentStep] != TransactionProgramAction.ZERO_OR_ONE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    s.emptyTargets[s.currentStep] = targetStep;
    return StatusCode.OK;
  }

  StatusCode captureColumn(int column) {
    if (s.frozen || s.currentStep < 0 || s.currentExpression >= 0 || column < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = s.ensureCaptures(s.captureCount + 1);
    if (status.isOk()) s.captureColumns[s.captureCount++] = column;
    return status;
  }

  StatusCode endStep() {
    if (s.frozen || s.currentStep < 0 || s.currentExpression >= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    s.parameterCounts[s.currentStep] = s.parameterCount - s.firstParameters[s.currentStep];
    s.captureCounts[s.currentStep] = s.captureCount - s.firstCaptures[s.currentStep];
    s.stepCount++;
    s.currentStep = -1;
    return StatusCode.OK;
  }

  StatusCode freeze() { return TransactionProgramFreezer.freeze(s, owner); }

  StatusCode copyTo(TransactionProgramBuilder target) {
    if (!s.frozen || target == null || target == this) return StatusCode.INVALID_EXTERNAL_INPUT;
    return TransactionProgramCopier.copy(s, target);
  }

  void reset() { s.reset(); }

  private TransactionProgram owner;

  void attach(TransactionProgram program) { owner = program; }

  private StatusCode beginExpression(boolean guard, int target) {
    if (s.frozen || s.currentStep < 0 || s.currentExpression >= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (guard && s.guards[s.currentStep] >= 0) return StatusCode.CONFLICT;
    StatusCode status = s.ensureExpressions(s.expressionCount + 1);
    if (status.isOk() && !guard) status = s.ensureParameters(s.parameterCount + 1);
    if (!status.isOk()) return status;
    s.currentExpression = s.expressionCount;
    s.expressionFirstNodes[s.currentExpression] = s.nodeCount;
    s.currentStackDepth = 0;
    s.currentGuard = guard;
    return StatusCode.OK;
  }

  private StatusCode appendNode(int operator, int first, int second, int descriptor) {
    StatusCode status = appendRawNode(operator, first, second, descriptor);
    if (!status.isOk()) return status;
    status = s.ensureTypeStack(s.currentStackDepth + 1);
    if (status.isOk()) {
      s.typeStack[s.currentStackDepth++] = descriptor;
      if (s.currentStackDepth > s.maximumStackDepth) s.maximumStackDepth = s.currentStackDepth;
      return StatusCode.OK;
    }
    s.nodeCount--;
    return status;
  }

  private StatusCode appendRawNode(int operator, int first, int second, int descriptor) {
    StatusCode status = s.ensureNodes(s.nodeCount + 1);
    if (!status.isOk()) return status;
    s.nodeOperators[s.nodeCount] = operator;
    s.nodeFirst[s.nodeCount] = first;
    s.nodeSecond[s.nodeCount] = second;
    s.nodeDescriptors[s.nodeCount] = descriptor;
    s.referenceNext[s.nodeCount] = -1;
    s.nodeCount++;
    return StatusCode.OK;
  }

  private boolean expressionOpen() { return s.currentStep >= 0 && s.currentExpression >= 0; }
}
