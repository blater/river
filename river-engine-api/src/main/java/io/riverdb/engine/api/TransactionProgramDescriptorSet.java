package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Primitive descriptor identity table for argument and prior-result leaves. */
final class TransactionProgramDescriptorSet {
  private final long[] keys;
  private final int[] values;

  TransactionProgramDescriptorSet(int nodeCount) {
    int capacity = TransactionProgramValidationSizing.tableCapacity(nodeCount);
    keys = new long[capacity];
    values = new int[capacity];
  }

  static long retainedBytes(int nodeCount) {
    int capacity = TransactionProgramValidationSizing.tableCapacity(nodeCount);
    if (capacity < 0) return -1;
    long bytes = TransactionProgramStorage.arrayBytes(capacity, Long.BYTES);
    return TransactionProgramValidationSizing.add(
        bytes, TransactionProgramStorage.arrayBytes(capacity, Integer.BYTES));
  }

  StatusCode validate(TransactionProgram program) {
    for (int node = 0; node < program.nodeCount(); node++) {
      long key = key(program, node);
      if (key == Long.MAX_VALUE) continue;
      if (!remember(key, program.nodeDescriptor(node))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    int arguments = program.requiredArgumentSlots();
    if (arguments > program.nodeCount()) return StatusCode.INVALID_EXTERNAL_INPUT;
    for (int slot = 0; slot < arguments; slot++) {
      if (!contains((long) slot + 1L)) return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }

  private long key(TransactionProgram program, int node) {
    int operator = program.nodeOperator(node);
    if (operator == TransactionScalarOperator.ARGUMENT) {
      return (long) program.nodeFirst(node) + 1L;
    }
    if (operator == TransactionScalarOperator.RESULT) {
      return Long.MIN_VALUE | (long) program.nodeFirst(node) << 32
          | program.nodeSecond(node) & 0xffff_ffffL;
    }
    return Long.MAX_VALUE;
  }

  private boolean remember(long key, int descriptor) {
    int mask = values.length - 1;
    int slot = mix(key) & mask;
    while (true) {
      int existing = values[slot];
      if (existing == 0) {
        keys[slot] = key;
        values[slot] = descriptor;
        return true;
      }
      if (keys[slot] == key) return existing == descriptor;
      slot = slot + 1 & mask;
    }
  }

  private boolean contains(long key) {
    int slot = mix(key) & values.length - 1;
    while (values[slot] != 0) {
      if (keys[slot] == key) return true;
      slot = slot + 1 & values.length - 1;
    }
    return false;
  }

  private static int mix(long value) {
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    return (int) value ^ (int) (value >>> 32);
  }
}
