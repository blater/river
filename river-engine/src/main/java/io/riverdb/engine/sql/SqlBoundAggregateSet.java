package io.riverdb.engine.sql;

/** Statement-owned resolved descriptors and physical operand lanes for aggregates. */
final class SqlBoundAggregateSet {
  private static final int MAXIMUM_INVOCATIONS = 8;
  private final byte[] kinds = new byte[MAXIMUM_INVOCATIONS];
  private final byte[] operandLanes = new byte[MAXIMUM_INVOCATIONS];
  private final int[] inputDescriptors = new int[MAXIMUM_INVOCATIONS];
  private final int[] resultDescriptors = new int[MAXIMUM_INVOCATIONS];
  private int count;

  void reset() {
    for (int index = 0; index < count; index++) {
      kinds[index] = 0;
      operandLanes[index] = 0;
      inputDescriptors[index] = 0;
      resultDescriptors[index] = 0;
    }
    count = 0;
  }

  void append(int kind, int lane, int inputDescriptor, int resultDescriptor) {
    int index = count++;
    kinds[index] = (byte) kind;
    operandLanes[index] = (byte) lane;
    inputDescriptors[index] = inputDescriptor;
    resultDescriptors[index] = resultDescriptor;
  }

  int count() { return count; }
  int kind(int invocation) { return Byte.toUnsignedInt(kinds[invocation]); }
  int operandLane(int invocation) { return operandLanes[invocation]; }
  int inputDescriptor(int invocation) { return inputDescriptors[invocation]; }
  int resultDescriptor(int invocation) { return resultDescriptors[invocation]; }
}
