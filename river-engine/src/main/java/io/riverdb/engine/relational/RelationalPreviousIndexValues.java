package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Retained actual-count values captured before secondary-index maintenance. */
final class RelationalPreviousIndexValues {
  private long[] values = new long[0];
  private boolean[] nulls = new boolean[0];

  StatusCode reserve(int required) {
    if (required <= values.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, required, SqlShapeLimits.MAX_SECONDARY_INDEXES, 4);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      long[] grownValues = new long[capacity];
      boolean[] grownNulls = new boolean[capacity];
      values = grownValues;
      nulls = grownNulls;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void set(int slot, long value, boolean isNull) {
    values[slot] = value;
    nulls[slot] = isNull;
  }

  long value(int slot) {
    return values[slot];
  }

  boolean isNull(int slot) {
    return nulls[slot];
  }

  boolean same(int slot, boolean nextNull, long nextValue) {
    return nulls[slot] == nextNull && (nextNull || values[slot] == nextValue);
  }
}
