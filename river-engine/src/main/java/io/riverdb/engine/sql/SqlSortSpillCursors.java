package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Fan-in-bounded primitive cursor and heap storage for legacy record merging. */
final class SqlSortSpillCursors {
  private static final long[] EMPTY_LONGS = new long[0];
  private static final int[] EMPTY_INTS = new int[0];
  private final SqlSessionShapeBudget budget;
  private long[] offsets = EMPTY_LONGS;
  private long[] remaining = EMPTY_LONGS;
  private int[] heap = EMPTY_INTS;
  private long retainedBytes;

  SqlSortSpillCursors(SqlSessionShapeBudget shapeBudget) { budget = shapeBudget; }

  StatusCode reserve(int fanIn) {
    if (fanIn < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (offsets.length >= fanIn) return StatusCode.OK;
    long bytes = (long) fanIn * (2L * Long.BYTES + Integer.BYTES);
    long delta = bytes - retainedBytes;
    StatusCode status = budget == null || delta <= 0
        ? StatusCode.OK : budget.reserve(delta);
    if (!status.isOk()) return status;
    try {
      long[] nextOffsets = new long[fanIn];
      long[] nextRemaining = new long[fanIn];
      int[] nextHeap = new int[fanIn];
      offsets = nextOffsets;
      remaining = nextRemaining;
      heap = nextHeap;
      retainedBytes = bytes;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      if (budget != null && delta > 0) budget.rollback(delta);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long retainedBytes() { return retainedBytes; }

  long requiredBytes(int fanIn) {
    return fanIn < 2 ? Long.MAX_VALUE : offsets.length >= fanIn
        ? retainedBytes : cleanRequiredBytes(fanIn);
  }

  static long cleanRequiredBytes(int fanIn) {
    return fanIn < 2 ? Long.MAX_VALUE
        : (long) fanIn * (2L * Long.BYTES + Integer.BYTES);
  }

  void releaseRetainedStorage() {
    offsets = EMPTY_LONGS;
    remaining = EMPTY_LONGS;
    heap = EMPTY_INTS;
    retainedBytes = 0;
  }

  long[] offsets() { return offsets; }
  long[] remaining() { return remaining; }
  int[] heap() { return heap; }
}
