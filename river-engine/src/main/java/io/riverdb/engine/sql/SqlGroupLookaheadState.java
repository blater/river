package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Retained typed lookahead row for grouped scan boundaries. */
final class SqlGroupLookaheadState {
  private final SqlRetainedArrayAllocator allocator;
  private long[] values = new long[0];
  private long[] highs = new long[0];
  private long[] nullWords = new long[0];
  private int nullWordCount;
  private boolean available;
  private boolean inputExhausted;

  SqlGroupLookaheadState(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  void reset() {
    for (int word = 0; word < nullWordCount; word++) nullWords[word] = 0;
    for (int lane = 0; lane < values.length; lane++) {
      highs[lane] = 0;
      values[lane] = 0;
    }
    nullWordCount = 0;
    available = false;
    inputExhausted = false;
  }

  StatusCode reserve(int count) {
    int capacity = BoundedArrayGrowth.capacity(
        values.length, count, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int requiredWords = (count + Long.SIZE - 1) >>> 6;
    int maximumWords = (SqlShapeLimits.MAX_RESULT_COLUMNS + Long.SIZE - 1) >>> 6;
    int wordCapacity = BoundedArrayGrowth.capacity(
        nullWords.length, requiredWords, maximumWords, 1);
    if (wordCapacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == values.length && capacity == highs.length
        && wordCapacity == nullWords.length) return StatusCode.OK;
    try {
      long[] nextValues = capacity == values.length ? values : allocator.longs(capacity);
      long[] nextHighs = capacity == highs.length ? highs : allocator.longs(capacity);
      long[] nextNulls = wordCapacity == nullWords.length
          ? nullWords : allocator.longs(wordCapacity);
      values = nextValues;
      highs = nextHighs;
      nullWords = nextNulls;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode set(long[] sourceHighs, long[] sourceValues, int count, SqlNullWords nulls) {
    int words = (count + Long.SIZE - 1) >>> 6;
    if (count < 0 || count > values.length
        || sourceHighs == null || sourceHighs.length < count
        || sourceValues == null || sourceValues.length < count
        || nulls == null || nulls.nullWordCount() != words) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    System.arraycopy(sourceHighs, 0, highs, 0, count);
    System.arraycopy(sourceValues, 0, values, 0, count);
    for (int word = 0; word < words; word++) nullWords[word] = nulls.nullWord(word);
    nullWordCount = words;
    available = true;
    return StatusCode.OK;
  }

  StatusCode take(long[] destination, int count, SqlProjectedRow projected) {
    System.arraycopy(values, 0, destination, 0, count);
    projected.reset(count);
    if (!projected.status().isOk()) return projected.status();
    for (int lane = 0; lane < count; lane++) {
      if ((nullWords[lane >>> 6] >>> (lane & 63) & 1) != 0) projected.setNull(lane);
      else projected.setDecimal128(lane, highs[lane], destination[lane]);
    }
    available = false;
    return StatusCode.OK;
  }

  boolean available() { return available; }
  boolean inputExhausted() { return inputExhausted; }
  void exhaustInput() { inputExhausted = true; }
}
