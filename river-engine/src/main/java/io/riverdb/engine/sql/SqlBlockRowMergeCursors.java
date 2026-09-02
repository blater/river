package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Fan-in-bounded primitive cursor and heap storage for block-row merging. */
final class SqlBlockRowMergeCursors {
  private final SqlSessionShapeBudget budget;
  private long[] positions = new long[0];
  private long[] ends = new long[0];
  private long[] heads = new long[0];
  private int[] heap = new int[0];
  private long retainedBytes;

  SqlBlockRowMergeCursors(SqlSessionShapeBudget shapeBudget) { budget = shapeBudget; }

  StatusCode reserve(int fanIn) {
    if (fanIn < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (positions.length >= fanIn) return StatusCode.OK;
    long bytes = (long) fanIn * (3L * Long.BYTES + Integer.BYTES);
    long delta = bytes - retainedBytes;
    StatusCode status = budget == null || delta <= 0
        ? StatusCode.OK : budget.reserve(delta);
    if (!status.isOk()) return status;
    try {
      long[] nextPositions = new long[fanIn];
      long[] nextEnds = new long[fanIn];
      long[] nextHeads = new long[fanIn];
      int[] nextHeap = new int[fanIn];
      positions = nextPositions;
      ends = nextEnds;
      heads = nextHeads;
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

  long[] positions() { return positions; }
  long[] ends() { return ends; }
  long[] heads() { return heads; }
  int[] heap() { return heap; }
}
