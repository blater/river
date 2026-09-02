package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable decoded row pair and public output view for spill comparison/consumption. */
final class SqlSortSpillDecodedRows {
  long[] keys = new long[0];
  long[] keyHighs = new long[0];
  long[] primaryKeys = new long[0];
  long[] ordinals = new long[0];
  boolean[] keyNulls = new boolean[0];
  int[] rowLengths = new int[0];
  final SqlSortNullWords nulls;
  final HeapRowResult outputView = new HeapRowResult();
  long[] values = new long[0];
  long[] highs = new long[0];
  ByteBuffer rows;
  ByteBuffer outputRow;
  int outputRowLength;
  long outputPrimaryKey;

  SqlSortSpillDecodedRows(SqlRetainedArrayAllocator allocator) {
    nulls = new SqlSortNullWords(2, allocator);
  }

  StatusCode reserve(int projections, boolean text, SqlRetainedArrayAllocator allocator) {
    return reserve(projections, text, 2, allocator);
  }

  StatusCode reserve(
      int projections, boolean text, int slots, SqlRetainedArrayAllocator allocator) {
    long requiredLong = (long) slots * projections;
    if (requiredLong > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    int required = (int) requiredLong;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, required, Integer.MAX_VALUE, Math.max(16, slots));
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      long[] nextValues = capacity == values.length ? values : allocator.longs(capacity);
      long[] nextHighs = capacity == highs.length ? highs : allocator.longs(capacity);
      long[] nextKeys = keys.length >= slots ? keys : allocator.longs(slots);
      long[] nextKeyHighs = keyHighs.length >= slots ? keyHighs : allocator.longs(slots);
      long[] nextPrimary = primaryKeys.length >= slots ? primaryKeys : allocator.longs(slots);
      long[] nextOrdinals = ordinals.length >= slots ? ordinals : allocator.longs(slots);
      boolean[] nextNulls = keyNulls.length >= slots ? keyNulls : allocator.booleans(slots);
      int[] nextLengths = rowLengths.length >= slots ? rowLengths : allocator.integers(slots);
      nulls.maximumRows(slots);
      StatusCode status = nulls.reserve(projections, SqlShapeLimits.MAX_RESULT_COLUMNS);
      if (!status.isOk()) return status;
      long rowBytes = (long) slots * TableSchema.MAXIMUM_ROW_BYTES;
      if (text && (rows == null || rows.capacity() < rowBytes)) {
        if (rowBytes > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
        rows = allocator.direct((int) rowBytes);
      }
      if (text && outputRow == null) {
        outputRow = allocator.direct(TableSchema.MAXIMUM_ROW_BYTES);
      }
      keys = nextKeys;
      keyHighs = nextKeyHighs;
      primaryKeys = nextPrimary;
      ordinals = nextOrdinals;
      keyNulls = nextNulls;
      rowLengths = nextLengths;
      values = nextValues;
      highs = nextHighs;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
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
}
