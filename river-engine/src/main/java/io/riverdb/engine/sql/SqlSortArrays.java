package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Primitive row and projection lanes retained by one in-memory sort run. */
final class SqlSortArrays {
  private final SqlRetainedArrayAllocator allocator;
  private long[] keys;
  private long[] keyHighs;
  private long[] primaryKeys;
  private long[] ordinals;
  private final SqlSortProjectionArrays projections;
  private boolean[] keyNulls;
  private int[] rowSlots;
  private int[] rowLengths;

  SqlSortArrays(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
    projections = new SqlSortProjectionArrays(allocator);
  }

  StatusCode reserve(int rows, int projections) {
    try {
      long[] nextKeys = keys == null || keys.length < rows ? allocator.longs(rows) : keys;
      long[] nextKeyHighs = keyHighs == null || keyHighs.length < rows
          ? allocator.longs(rows) : keyHighs;
      long[] nextPrimary = primaryKeys == null || primaryKeys.length < rows
          ? allocator.longs(rows) : primaryKeys;
      long[] nextOrdinals = ordinals == null || ordinals.length < rows
          ? allocator.longs(rows) : ordinals;
      boolean[] nextKeyNulls = keyNulls == null || keyNulls.length < rows
          ? allocator.booleans(rows) : keyNulls;
      int[] nextSlots = rowSlots == null || rowSlots.length < rows
          ? allocator.integers(rows) : rowSlots;
      int[] nextLengths = rowLengths == null || rowLengths.length < rows
          ? allocator.integers(rows) : rowLengths;
      StatusCode status = this.projections.reserve(rows, projections);
      if (!status.isOk()) return status;
      keys = nextKeys;
      keyHighs = nextKeyHighs;
      primaryKeys = nextPrimary;
      ordinals = nextOrdinals;
      keyNulls = nextKeyNulls;
      rowSlots = nextSlots;
      rowLengths = nextLengths;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void append(
      int row,
      int projections,
      long ordinal,
      long keyHigh,
      long key,
      boolean keyNull,
      long primaryKey,
      long[] projectedHighs,
      long[] projectedValues) {
    keyHighs[row] = keyHigh;
    keys[row] = key;
    keyNulls[row] = keyNull;
    primaryKeys[row] = primaryKey;
    ordinals[row] = ordinal;
    this.projections.append(row, projections, projectedHighs, projectedValues);
  }

  void swap(int left, int right, int projections) {
    swap(keyHighs, left, right);
    swap(keys, left, right);
    swap(primaryKeys, left, right);
    swap(ordinals, left, right);
    swap(keyNulls, left, right);
    swap(rowSlots, left, right);
    this.projections.swap(left, right, projections);
  }

  void copyValues(int row, int projections, int count, long[] target) {
    this.projections.copyValues(row, projections, count, target);
  }

  void copyHighs(int row, int projections, int count, long[] target) {
    this.projections.copyHighs(row, projections, count, target);
  }

  long retainedProjectionBytes() {
    return projections.retainedBytes();
  }

  long retainedBytes() {
    return bytes(keys) + bytes(keyHighs) + bytes(primaryKeys) + bytes(ordinals)
        + (keyNulls == null ? 0 : keyNulls.length)
        + bytes(rowSlots) + bytes(rowLengths) + projections.retainedBytes();
  }

  long requiredBytes(int rows, int projections) {
    long projectionBytes = this.projections.requiredBytes(rows, projections);
    if (projectionBytes == Long.MAX_VALUE) return Long.MAX_VALUE;
    long rowLongs = (long) Math.max(rows, keys == null ? 0 : keys.length)
        + Math.max(rows, keyHighs == null ? 0 : keyHighs.length)
        + Math.max(rows, primaryKeys == null ? 0 : primaryKeys.length)
        + Math.max(rows, ordinals == null ? 0 : ordinals.length);
    long rowInts = (long) Math.max(rows, rowSlots == null ? 0 : rowSlots.length)
        + Math.max(rows, rowLengths == null ? 0 : rowLengths.length);
    long nullBytes = Math.max(rows, keyNulls == null ? 0 : keyNulls.length);
    return rowLongs * Long.BYTES + rowInts * Integer.BYTES + nullBytes
        + projectionBytes;
  }

  static long cleanRequiredBytes(int rows, int projections) {
    long projectionBytes = SqlSortProjectionArrays.cleanRequiredBytes(rows, projections);
    if (projectionBytes == Long.MAX_VALUE) return Long.MAX_VALUE;
    long rowLongs = 4L * rows;
    long rowInts = 2L * rows;
    return rowLongs * Long.BYTES + rowInts * Integer.BYTES + rows
        + projectionBytes;
  }

  void release() {
    keys = null;
    keyHighs = null;
    primaryKeys = null;
    ordinals = null;
    keyNulls = null;
    rowSlots = null;
    rowLengths = null;
    projections.release();
  }

  long[] keys() { return keys; }
  long[] keyHighs() { return keyHighs; }
  long[] primaryKeys() { return primaryKeys; }
  long[] ordinals() { return ordinals; }
  long[] highs() { return projections.highs(); }
  long[] values() { return projections.values(); }
  boolean[] keyNulls() { return keyNulls; }
  int[] rowSlots() { return rowSlots; }
  int[] rowLengths() { return rowLengths; }
  long primaryKey(int row) { return primaryKeys[row]; }
  int rowSlot(int row) { return rowSlots[row]; }
  int rowLengthAtSlot(int slot) { return rowLengths[slot]; }
  void rowLocation(int row, int slot, int length) {
    rowSlots[row] = slot;
    rowLengths[slot] = length;
  }

  private static void swap(long[] array, int left, int right) {
    long value = array[left]; array[left] = array[right]; array[right] = value;
  }

  private static void swap(int[] array, int left, int right) {
    int value = array[left]; array[left] = array[right]; array[right] = value;
  }

  private static void swap(boolean[] array, int left, int right) {
    boolean value = array[left]; array[left] = array[right]; array[right] = value;
  }

  private static long bytes(long[] array) {
    return array == null ? 0 : (long) array.length * Long.BYTES;
  }

  private static long bytes(int[] array) {
    return array == null ? 0 : (long) array.length * Integer.BYTES;
  }
}
