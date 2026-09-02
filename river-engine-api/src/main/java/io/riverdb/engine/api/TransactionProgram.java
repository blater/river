package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Immutable-after-freeze transaction structure with a reusable primitive graph. */
public final class TransactionProgram {
  private final TransactionProgramStorage storage;
  private final TransactionProgramBuilder builder;

  public TransactionProgram() {
    this(RetainedMemoryLease.unbounded());
  }

  public TransactionProgram(RetainedMemoryLease retainedMemory) {
    if (retainedMemory == null) throw new IllegalArgumentException("retainedMemory");
    storage = new TransactionProgramStorage(retainedMemory);
    builder = new TransactionProgramBuilder(storage);
    builder.attach(this);
  }

  public StatusCode beginStep(long preparedHandle, int action) {
    return builder.beginStep(preparedHandle, action);
  }
  public StatusCode beginParameter() { return builder.beginParameter(); }
  public StatusCode requireAffectedRows(long minimum, long maximum) {
    return builder.requireAffectedRows(minimum, maximum);
  }
  public StatusCode beginGuard(int falseTarget) { return builder.beginGuard(falseTarget); }
  public StatusCode argument(int slot, int descriptor) { return builder.argument(slot, descriptor); }
  public StatusCode priorResult(int step, int column, int descriptor) {
    return builder.priorResult(step, column, descriptor);
  }
  public StatusCode nullValue(int descriptor) { return builder.nullValue(descriptor); }
  public StatusCode operator(int operator, int targetDescriptor) {
    return builder.operator(operator, targetDescriptor);
  }
  public StatusCode endExpression() { return builder.endExpression(); }
  public StatusCode skipOnEmpty(int targetStep) { return builder.skipOnEmpty(targetStep); }
  public StatusCode captureColumn(int column) { return builder.captureColumn(column); }
  public StatusCode endStep() { return builder.endStep(); }
  public StatusCode freeze() { return builder.freeze(); }

  public boolean isFrozen() { return storage.frozen; }
  public int stepCount() { return storage.stepCount; }
  public int requiredArgumentSlots() { return storage.maximumArgumentSlot; }
  public long retainedBytes() { return storage.retainedBytes + storage.validationBytes; }
  public static long maximumRetainedBytes(
      int steps, int parameters, int captures, int expressions, int nodes) {
    return TransactionProgramStorageSizing.maximumRetainedBytes(
        steps, parameters, captures, expressions, nodes);
  }

  /** Rebuilds this canonical frozen graph into caller-owned retained storage. */
  public StatusCode copyTo(TransactionProgram target) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return builder.copyTo(target.builder);
  }

  /** Clears one decoded/built graph while retaining its admitted primitive storage. */
  public void reset() { storage.reset(); }
  public StatusCode release() { return storage.release(); }

  public long preparedHandle(int step) { return storage.handles[step]; }
  public int action(int step) { return storage.actions[step]; }
  public long minimumAffectedRows(int step) { return storage.minimumAffectedRows[step]; }
  public long maximumAffectedRows(int step) { return storage.maximumAffectedRows[step]; }
  public int firstParameter(int step) { return storage.firstParameters[step]; }
  public int parameterCount(int step) { return storage.parameterCounts[step]; }
  public int parameterExpression(int parameter) { return storage.parameterExpressions[parameter]; }
  public int guardExpression(int step) { return storage.guards[step]; }
  public int falseTarget(int step) { return storage.falseTargets[step]; }
  public int emptyTarget(int step) { return storage.emptyTargets[step]; }
  public int firstCapture(int step) { return storage.firstCaptures[step]; }
  public int captureCount(int step) { return storage.captureCounts[step]; }
  public int captureColumnAt(int capture) { return storage.captureColumns[capture]; }
  public int expressionFirstNode(int expression) { return storage.expressionFirstNodes[expression]; }
  public int expressionNodeCount(int expression) { return storage.expressionNodeCounts[expression]; }
  public int expressionDescriptor(int expression) { return storage.expressionDescriptors[expression]; }
  public int nodeOperator(int node) { return storage.nodeOperators[node]; }
  public int nodeFirst(int node) { return storage.nodeFirst[node]; }
  public int nodeSecond(int node) { return storage.nodeSecond[node]; }
  public int nodeDescriptor(int node) { return storage.nodeDescriptors[node]; }
  public int referenceHead(int step) { return storage.referenceHeads[step]; }
  public int referenceNext(int node) { return storage.referenceNext[node]; }
  public int maximumStackDepth() { return storage.maximumStackDepth; }
  public int nodeCount() { return storage.nodeCount; }

  StatusCode reserveValidation(long bytes) { return storage.reserveValidation(bytes); }
  StatusCode releaseValidation() { return storage.releaseValidation(); }
}
