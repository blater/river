package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Budgeted high-water storage for one resolved time-zone transition table. */
final class SqlTemporalTransitionStorage {
  private static final long[] NO_SECONDS = new long[0];
  private static final int[] NO_OFFSETS = new int[0];
  private static final int MAXIMUM_TRANSITIONS = 4_096;
  private static final long ARRAY_HEADER_BYTES = 16;
  private final SqlRetainedArrayAllocator allocator;
  private final SqlSessionShapeBudget budget;
  long[] seconds = NO_SECONDS;
  int[] before = NO_OFFSETS;
  int[] after = NO_OFFSETS;

  SqlTemporalTransitionStorage(
      SqlRetainedArrayAllocator retainedAllocator, SqlSessionShapeBudget shapeBudget) {
    allocator = retainedAllocator;
    budget = shapeBudget;
  }

  StatusCode reserve(int required) {
    int capacity = BoundedArrayGrowth.capacity(
        seconds.length, required, MAXIMUM_TRANSITIONS, 16);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (capacity == seconds.length) return StatusCode.OK;
    long charged = retainedBytes(capacity) - retainedBytes(seconds.length);
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      long[] nextSeconds = allocator.longs(capacity);
      int[] nextBefore = allocator.integers(capacity);
      int[] nextAfter = allocator.integers(capacity);
      seconds = nextSeconds;
      before = nextBefore;
      after = nextAfter;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int capacity() { return seconds.length; }

  private static long retainedBytes(int capacity) {
    if (capacity == 0) return 0;
    return align(ARRAY_HEADER_BYTES + (long) Long.BYTES * capacity)
        + 2 * align(ARRAY_HEADER_BYTES + (long) Integer.BYTES * capacity);
  }

  private static long align(long bytes) { return bytes + 7 & ~7L; }
}
