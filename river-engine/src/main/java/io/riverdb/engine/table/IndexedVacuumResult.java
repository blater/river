package io.riverdb.engine.table;

/** Caller-owned result for one quiescent indexed-table vacuum. */
public final class IndexedVacuumResult {
  private int rowsBefore;
  private int rowsAfter;
  private long commitSequence;

  public void reset() {
    rowsBefore = 0;
    rowsAfter = 0;
    commitSequence = 0;
  }

  public void set(int before, int after, long committedAt) {
    rowsBefore = before;
    rowsAfter = after;
    commitSequence = committedAt;
  }

  public int rowsBefore() {
    return rowsBefore;
  }

  public int rowsAfter() {
    return rowsAfter;
  }

  public int rowsReclaimed() {
    return rowsBefore - rowsAfter;
  }

  public long commitSequence() {
    return commitSequence;
  }
}
