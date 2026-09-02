package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Retained primitive stack storage for deterministic CHECK evaluation. */
final class TableCheckStack {
  long[] values = new long[0];
  int[] descriptors = new int[0];
  boolean[] nulls = new boolean[0];

  StatusCode reserve(int required) {
    if (required <= values.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, required, SqlShapeLimits.MAX_EXPRESSION_NODES, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      long[] grownValues = new long[capacity];
      int[] grownDescriptors = new int[capacity];
      boolean[] grownNulls = new boolean[capacity];
      values = grownValues;
      descriptors = grownDescriptors;
      nulls = grownNulls;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
