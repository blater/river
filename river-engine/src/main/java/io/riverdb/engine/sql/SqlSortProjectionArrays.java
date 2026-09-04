package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Retained row-major high and low projection lanes for one sort run. */
final class SqlSortProjectionArrays {
  private final SqlRetainedArrayAllocator allocator;
  private long[] highs;
  private long[] values;

  SqlSortProjectionArrays(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode reserve(int rows, int projections) {
    int capacity = capacity(values == null ? 0 : values.length, rows, projections);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      long[] nextValues = values == null || capacity != values.length
          ? allocator.longs(capacity) : values;
      long[] nextHighs = highs == null || capacity != highs.length
          ? allocator.longs(capacity) : highs;
      values = nextValues;
      highs = nextHighs;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void append(int row, int projections, long[] sourceHighs, long[] sourceValues) {
    int start = row * projections;
    for (int index = 0; index < projections; index++) {
      highs[start + index] = sourceHighs[index];
      values[start + index] = sourceValues[index];
    }
  }

  void swap(int left, int right, int projections) {
    int leftStart = left * projections;
    int rightStart = right * projections;
    for (int index = 0; index < projections; index++) {
      swap(highs, leftStart + index, rightStart + index);
      swap(values, leftStart + index, rightStart + index);
    }
  }

  void copyValues(int row, int projections, int count, long[] target) {
    System.arraycopy(values, row * projections, target, 0, count);
  }

  void copyHighs(int row, int projections, int count, long[] target) {
    System.arraycopy(highs, row * projections, target, 0, count);
  }

  long retainedBytes() {
    return values == null ? 0 : (long) (values.length + highs.length) * Long.BYTES;
  }

  long requiredBytes(int rows, int projections) {
    int capacity = capacity(values == null ? 0 : values.length, rows, projections);
    return capacity < 0 ? Long.MAX_VALUE : 2L * capacity * Long.BYTES;
  }

  static long cleanRequiredBytes(int rows, int projections) {
    int capacity = capacity(0, rows, projections);
    return capacity < 0 ? Long.MAX_VALUE : 2L * capacity * Long.BYTES;
  }

  void release() {
    values = null;
    highs = null;
  }

  long[] highs() { return highs; }
  long[] values() { return values; }

  private static int capacity(int current, int rows, int projections) {
    long required = (long) rows * projections;
    return required > Integer.MAX_VALUE ? -1
        : BoundedArrayGrowth.capacity(current, (int) required, Integer.MAX_VALUE, rows);
  }

  private static void swap(long[] array, int left, int right) {
    long value = array[left]; array[left] = array[right]; array[right] = value;
  }
}
