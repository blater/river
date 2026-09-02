package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Statement-owned resolved descriptors and physical operand lanes for aggregates. */
final class SqlBoundAggregateSet {
  private final SqlSessionShapeBudget budget;
  private byte[] kinds = new byte[0];
  private int[] operandLanes = new int[0];
  private int[] inputDescriptors = new int[0];
  private int[] resultDescriptors = new int[0];
  private int count;

  SqlBoundAggregateSet(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  SqlBoundAggregateSet() {
    this(new SqlSessionShapeBudget(null));
  }

  StatusCode reserve(int required) {
    int capacity = BoundedArrayGrowth.capacity(
        kinds.length, required, SqlShapeLimits.MAX_AGGREGATES, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == kinds.length) return StatusCode.OK;
    long charged = (long) (capacity - kinds.length)
        * (Byte.BYTES + 3L * Integer.BYTES);
    StatusCode status = budget.reserve(charged);
    if (!status.isOk()) return status;
    try {
      byte[] nextKinds = new byte[capacity];
      int[] nextLanes = new int[capacity];
      int[] nextInputs = new int[capacity];
      int[] nextResults = new int[capacity];
      System.arraycopy(kinds, 0, nextKinds, 0, count);
      System.arraycopy(operandLanes, 0, nextLanes, 0, count);
      System.arraycopy(inputDescriptors, 0, nextInputs, 0, count);
      System.arraycopy(resultDescriptors, 0, nextResults, 0, count);
      kinds = nextKinds;
      operandLanes = nextLanes;
      inputDescriptors = nextInputs;
      resultDescriptors = nextResults;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      kinds[index] = 0;
      operandLanes[index] = 0;
      inputDescriptors[index] = 0;
      resultDescriptors[index] = 0;
    }
    count = 0;
  }

  StatusCode copyFrom(SqlBoundAggregateSet source) {
    reset();
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = reserve(source.count);
    for (int invocation = 0; status.isOk() && invocation < source.count; invocation++) {
      append(
          source.kind(invocation),
          source.operandLane(invocation),
          source.inputDescriptor(invocation),
          source.resultDescriptor(invocation));
    }
    return status;
  }

  StatusCode copyDirectFrom(
      SqlBoundAggregateSet source, SqlBoundProjectionPrograms programs) {
    reset();
    if (source == null || programs == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = reserve(source.count);
    for (int invocation = 0;
        status.isOk() && invocation < source.count; invocation++) {
      int lane = source.operandLane(invocation);
      int directLane = lane < 0 ? lane : programs.rawColumn(lane);
      if (lane >= 0 && directLane < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      append(
          source.kind(invocation), directLane,
          source.inputDescriptor(invocation), source.resultDescriptor(invocation));
    }
    return status;
  }

  void append(int kind, int lane, int inputDescriptor, int resultDescriptor) {
    int index = count++;
    kinds[index] = (byte) kind;
    operandLanes[index] = lane;
    inputDescriptors[index] = inputDescriptor;
    resultDescriptors[index] = resultDescriptor;
  }

  int count() { return count; }
  int kind(int invocation) { return Byte.toUnsignedInt(kinds[invocation]); }
  int operandLane(int invocation) { return operandLanes[invocation]; }
  int inputDescriptor(int invocation) { return inputDescriptors[invocation]; }
  int resultDescriptor(int invocation) { return resultDescriptors[invocation]; }
}
