package io.riverdb.engine.table;

/** Caller-owned result for one quiescent indexed-table vacuum. */
public final class IndexedVacuumResult {
  private long rowsBefore;
  private long rowsAfter;
  private long commitSequence;

  public void reset() {
    rowsBefore = 0;
    rowsAfter = 0;
    commitSequence = 0;
  }

  public void set(long before, long after, long committedAt) {
    rowsBefore = before;
    rowsAfter = after;
    commitSequence = committedAt;
  }

  public long rowsBefore() {
    return rowsBefore;
  }

  public long rowsAfter() {
    return rowsAfter;
  }

  public long rowsReclaimed() {
    return rowsBefore - rowsAfter;
  }

  public long commitSequence() {
    return commitSequence;
  }
}
