package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable decoded row pair and public output view for spill comparison/consumption. */
final class SqlSortSpillDecodedRows {
  private static final long[] EMPTY_LONGS = new long[0];
  private static final boolean[] EMPTY_BOOLEANS = new boolean[0];
  private static final int[] EMPTY_INTS = new int[0];
  private final SqlSessionShapeBudget budget;
  long[] keys = EMPTY_LONGS;
  long[] keyHighs = EMPTY_LONGS;
  long[] primaryKeys = EMPTY_LONGS;
  long[] ordinals = EMPTY_LONGS;
  boolean[] keyNulls = EMPTY_BOOLEANS;
  int[] rowLengths = EMPTY_INTS;
  final SqlSortNullWords nulls;
  final HeapRowResult outputView = new HeapRowResult();
  long[] values = EMPTY_LONGS;
  long[] highs = EMPTY_LONGS;
  ByteBuffer rows;
  ByteBuffer outputRow;
  int outputRowLength;
  long outputPrimaryKey;
  private long retainedBytes;

  SqlSortSpillDecodedRows(
      SqlRetainedArrayAllocator allocator, SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
    nulls = new SqlSortNullWords(2, allocator);
  }

  StatusCode reserve(int projections, boolean text, SqlRetainedArrayAllocator allocator) {
    return reserve(projections, text, 2, allocator);
  }

  StatusCode reserve(
      int projections, boolean text, int slots, SqlRetainedArrayAllocator allocator) {
    long targetBytes = requiredBytes(projections, text, slots);
    if (targetBytes == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    long delta = targetBytes - retainedBytes;
    StatusCode admission = budget == null || delta <= 0
        ? StatusCode.OK : budget.reserve(delta);
    if (!admission.isOk()) return admission;
    try {
      int required = slots * projections;
      int capacity = BoundedArrayGrowth.capacity(
          Math.max(values.length, highs.length), required,
          Integer.MAX_VALUE, Math.max(16, slots));
      long[] nextValues = capacity == values.length ? values : allocator.longs(capacity);
      long[] nextHighs = capacity == highs.length ? highs : allocator.longs(capacity);
      long[] nextKeys = keys.length >= slots ? keys : allocator.longs(slots);
      long[] nextKeyHighs = keyHighs.length >= slots ? keyHighs : allocator.longs(slots);
      long[] nextPrimary = primaryKeys.length >= slots ? primaryKeys : allocator.longs(slots);
      long[] nextOrdinals = ordinals.length >= slots ? ordinals : allocator.longs(slots);
      boolean[] nextNulls = keyNulls.length >= slots ? keyNulls : allocator.booleans(slots);
      int[] nextLengths = rowLengths.length >= slots ? rowLengths : allocator.integers(slots);
      long rowBytes = (long) slots * TableSchema.MAXIMUM_ROW_BYTES;
      ByteBuffer nextRows = rows;
      if (text && (rows == null || rows.capacity() < rowBytes)) {
        nextRows = allocator.direct((int) rowBytes);
      }
      ByteBuffer nextOutput = text && outputRow == null
          ? allocator.direct(TableSchema.MAXIMUM_ROW_BYTES) : outputRow;
      StatusCode status = nulls.reserve(
          projections, SqlShapeLimits.MAX_RESULT_COLUMNS, slots);
      if (!status.isOk()) {
        if (budget != null && delta > 0) budget.rollback(delta);
        return status;
      }
      keys = nextKeys;
      keyHighs = nextKeyHighs;
      primaryKeys = nextPrimary;
      ordinals = nextOrdinals;
      keyNulls = nextNulls;
      rowLengths = nextLengths;
      values = nextValues;
      highs = nextHighs;
      rows = nextRows;
      outputRow = nextOutput;
      retainedBytes = targetBytes;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      if (budget != null && delta > 0) budget.rollback(delta);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long retainedBytes() { return retainedBytes; }

  static long cleanRequiredBytes(int projections, boolean text, int slots) {
    if (projections <= 0 || slots < 2) return Long.MAX_VALUE;
    long requiredLong = (long) slots * projections;
    if (requiredLong > Integer.MAX_VALUE) return Long.MAX_VALUE;
    int capacity = BoundedArrayGrowth.capacity(
        0, (int) requiredLong, Integer.MAX_VALUE, Math.max(16, slots));
    if (capacity < 0) return Long.MAX_VALUE;
    long bytes = 2L * capacity * Long.BYTES;
    bytes = SqlSortRunCapacity.add(bytes, 4L * slots * Long.BYTES);
    bytes = SqlSortRunCapacity.add(bytes, slots);
    bytes = SqlSortRunCapacity.add(bytes, (long) slots * Integer.BYTES);
    bytes = SqlSortRunCapacity.add(bytes, SqlSortNullWords.cleanRequiredBytes(
        projections, SqlShapeLimits.MAX_RESULT_COLUMNS, slots));
    if (text) {
      bytes = SqlSortRunCapacity.add(
          bytes, (long) slots * TableSchema.MAXIMUM_ROW_BYTES);
      bytes = SqlSortRunCapacity.add(bytes, TableSchema.MAXIMUM_ROW_BYTES);
    }
    return bytes;
  }

  void releaseRetainedStorage() {
    keys = EMPTY_LONGS;
    keyHighs = EMPTY_LONGS;
    primaryKeys = EMPTY_LONGS;
    ordinals = EMPTY_LONGS;
    keyNulls = EMPTY_BOOLEANS;
    rowLengths = EMPTY_INTS;
    values = EMPTY_LONGS;
    highs = EMPTY_LONGS;
    rows = null;
    outputRow = null;
    outputRowLength = 0;
    nulls.release();
    retainedBytes = 0;
  }

  void captureRow(int slot) {
    outputRowLength = rowLengths[slot];
    int sourceOffset = slot * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < outputRowLength; index++) {
      outputRow.put(index, rows.get(sourceOffset + index));
    }
  }

  HeapRowResult outputRow() {
    outputView.set(outputRow, 0, 0, outputRowLength);
    return outputView;
  }

  long requiredBytes(int projections, boolean text, int slots) {
    if (projections <= 0 || slots < 2) return Long.MAX_VALUE;
    long requiredLong = (long) slots * projections;
    if (requiredLong > Integer.MAX_VALUE) return Long.MAX_VALUE;
    int capacity = BoundedArrayGrowth.capacity(
        Math.max(values.length, highs.length), (int) requiredLong,
        Integer.MAX_VALUE, Math.max(16, slots));
    if (capacity < 0) return Long.MAX_VALUE;
    long bytes = 2L * capacity * Long.BYTES;
    bytes = SqlSortRunCapacity.add(bytes,
        (long) Math.max(keys.length, slots) * Long.BYTES);
    bytes = SqlSortRunCapacity.add(bytes,
        (long) Math.max(keyHighs.length, slots) * Long.BYTES);
    bytes = SqlSortRunCapacity.add(bytes,
        (long) Math.max(primaryKeys.length, slots) * Long.BYTES);
    bytes = SqlSortRunCapacity.add(bytes,
        (long) Math.max(ordinals.length, slots) * Long.BYTES);
    bytes = SqlSortRunCapacity.add(bytes, Math.max(keyNulls.length, slots));
    bytes = SqlSortRunCapacity.add(bytes,
        (long) Math.max(rowLengths.length, slots) * Integer.BYTES);
    bytes = SqlSortRunCapacity.add(bytes, nulls.requiredBytes(
        projections, SqlShapeLimits.MAX_RESULT_COLUMNS, slots));
    long rowBytes = rows == null ? 0 : rows.capacity();
    if (text) rowBytes = Math.max(rowBytes, (long) slots * TableSchema.MAXIMUM_ROW_BYTES);
    if (rowBytes > Integer.MAX_VALUE) return Long.MAX_VALUE;
    bytes = SqlSortRunCapacity.add(bytes, rowBytes);
    long outputBytes = outputRow == null ? 0 : outputRow.capacity();
    if (text) outputBytes = Math.max(outputBytes, TableSchema.MAXIMUM_ROW_BYTES);
    return SqlSortRunCapacity.add(bytes, outputBytes);
  }
}
