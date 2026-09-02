package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Transactionally grown primitive storage for one descriptor set shape. */
final class SqlDescriptorSetStorage {
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  int[] sources = new int[0];
  int[] outputs = new int[0];
  int[] sort = new int[0];
  int[] descriptors = new int[0];
  int[] aggregates = new int[0];
  boolean[] descending = new boolean[0];
  private int capacity;

  SqlDescriptorSetStorage(
      SqlRetainedArrayAllocator arrayAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = arrayAllocator;
    budget = shapeBudget;
  }

  StatusCode reserve(int required) {
    int next = BoundedArrayGrowth.capacity(
        capacity, required, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (next < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (next == capacity) return StatusCode.OK;
    long charged = (long) (next - capacity)
        * (Integer.BYTES * 5L + Byte.BYTES);
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      int[] nextSources = allocator.integers(next);
      int[] nextOutputs = allocator.integers(next);
      int[] nextSort = allocator.integers(next);
      int[] nextDescriptors = allocator.integers(next);
      int[] nextAggregates = allocator.integers(next);
      boolean[] nextDescending = allocator.booleans(next);
      sources = nextSources;
      outputs = nextOutputs;
      sort = nextSort;
      descriptors = nextDescriptors;
      aggregates = nextAggregates;
      descending = nextDescending;
      capacity = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
