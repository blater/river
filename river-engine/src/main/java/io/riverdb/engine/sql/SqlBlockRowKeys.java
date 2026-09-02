package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Retained public row identities kept parallel with encoded block rows. */
final class SqlBlockRowKeys {
  private static final long[] EMPTY = new long[0];
  private final int maximum;
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  private long[] values = EMPTY;

  SqlBlockRowKeys(int maximumRows, SqlRetainedArrayAllocator retainedAllocator) {
    this(maximumRows, retainedAllocator, null);
  }

  SqlBlockRowKeys(
      int maximumRows,
      SqlRetainedArrayAllocator retainedAllocator,
      SqlSessionShapeBudget shapeBudget) {
    maximum = maximumRows;
    allocator = retainedAllocator;
    budget = shapeBudget;
  }

  StatusCode ensure(int required, int used) {
    if (required <= values.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(values.length, required, maximum, 64);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long previousBytes = (long) values.length * Long.BYTES;
    long charged = (long) capacity * Long.BYTES;
    StatusCode admitted = budget == null ? StatusCode.OK : budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      long[] grown = allocator.longs(capacity);
      System.arraycopy(values, 0, grown, 0, used);
      java.util.Arrays.fill(values, 0);
      values = grown;
      if (budget != null && previousBytes > 0) budget.rollback(previousBytes);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (budget != null) budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void set(int row, long value) { values[row] = value; }
  long get(int row) { return values[row]; }
  long retainedBytes() { return (long) values.length * Long.BYTES; }

  void clear(int count) {
    for (int index = 0; index < Math.min(count, values.length); index++) values[index] = 0;
  }

  void close(int count) {
    clear(count);
  }
}
