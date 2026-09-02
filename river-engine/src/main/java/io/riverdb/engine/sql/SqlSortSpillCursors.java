package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Fan-in-bounded primitive cursor and heap storage for legacy record merging. */
final class SqlSortSpillCursors {
  private final SqlSessionShapeBudget budget;
  private long[] offsets = new long[0];
  private long[] remaining = new long[0];
  private int[] heap = new int[0];
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

  void close() {
    // Session-owned fan-in storage is bounded by configuration and retained for reuse.
  }

  long[] offsets() { return offsets; }
  long[] remaining() { return remaining; }
  int[] heap() { return heap; }
}
