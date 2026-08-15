package io.riverdb.sql;

/** Command-owned selected-output mapping for a deduplicated aggregate set. */
final class SqlAggregateSet {
  static final int MAXIMUM_INVOCATIONS = 8;

  private final byte[] kinds = new byte[MAXIMUM_INVOCATIONS];
  private final byte[] operandProjections = new byte[MAXIMUM_INVOCATIONS];
  private final byte[] outputInvocations = new byte[MAXIMUM_INVOCATIONS];
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

  void copyFrom(SqlAggregateSet source) {
    reset();
    invocationCount = source.invocationCount;
    outputCount = source.outputCount;
    System.arraycopy(source.kinds, 0, kinds, 0, invocationCount);
    System.arraycopy(
        source.operandProjections, 0, operandProjections, 0, invocationCount);
    System.arraycopy(source.outputInvocations, 0, outputInvocations, 0, outputCount);
  }

  int appendInvocation(int kind, int operandProjection) {
    if (invocationCount >= kinds.length) return -1;
    int invocation = invocationCount++;
    kinds[invocation] = (byte) kind;
    operandProjections[invocation] = (byte) operandProjection;
    return invocation;
  }

  boolean appendOutput(int invocation) {
    if (outputCount >= outputInvocations.length
        || invocation < 0 || invocation >= invocationCount) {
      return false;
    }
    outputInvocations[outputCount++] = (byte) invocation;
    return true;
  }

  int invocationCount() { return invocationCount; }
  int outputCount() { return outputCount; }
  int kind(int invocation) { return Byte.toUnsignedInt(kinds[invocation]); }
  int operandProjection(int invocation) { return operandProjections[invocation]; }
  int outputInvocation(int output) {
    return Byte.toUnsignedInt(outputInvocations[output]);
  }
}
