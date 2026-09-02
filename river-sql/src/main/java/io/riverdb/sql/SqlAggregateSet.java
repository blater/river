package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Command-owned selected-output mapping for a deduplicated aggregate set. */
final class SqlAggregateSet {
  static final int MAXIMUM_INVOCATIONS = SqlShapeLimits.MAX_AGGREGATES;

  int[] kinds = new int[8];
  int[] operandProjections = new int[8];
  int[] outputInvocations = new int[8];
  private int invocationCount;
  private int outputCount;

  void reset() {
    for (int index = 0; index < invocationCount; index++) {
      kinds[index] = 0;
      operandProjections[index] = 0;
    }
    for (int index = 0; index < outputCount; index++) outputInvocations[index] = 0;
    invocationCount = 0;
    outputCount = 0;
  }

  StatusCode copyFrom(SqlAggregateSet source) {
    reset();
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!SqlAggregateCapacity.ensure(this, source.invocationCount, source.outputCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    invocationCount = source.invocationCount;
    outputCount = source.outputCount;
    System.arraycopy(source.kinds, 0, kinds, 0, invocationCount);
    System.arraycopy(
        source.operandProjections, 0, operandProjections, 0, invocationCount);
    System.arraycopy(source.outputInvocations, 0, outputInvocations, 0, outputCount);
    return StatusCode.OK;
  }

  int appendInvocation(int kind, int operandProjection) {
    if (invocationCount >= MAXIMUM_INVOCATIONS
        || !SqlAggregateCapacity.ensure(this, invocationCount + 1, outputCount)) return -1;
    int invocation = invocationCount++;
    kinds[invocation] = kind;
    operandProjections[invocation] = operandProjection;
    return invocation;
  }

  boolean appendOutput(int invocation) {
    if (outputCount >= MAXIMUM_INVOCATIONS
        || !SqlAggregateCapacity.ensure(this, invocationCount, outputCount + 1)
        || invocation < 0 || invocation >= invocationCount) {
      return false;
    }
    outputInvocations[outputCount++] = invocation;
    return true;
  }

  int invocationCount() { return invocationCount; }
  int outputCount() { return outputCount; }
  int kind(int invocation) { return kinds[invocation]; }
  int operandProjection(int invocation) { return operandProjections[invocation]; }
  int outputInvocation(int output) {
    return outputInvocations[output];
  }
}
