package io.riverdb.engine.table;

/** Caller-owned row identity and commit CSN from one indexed transaction write. */
public final class IndexedCommitResult {
  private int rowId;
  private long commitSequence;

  public int rowId() {
    return rowId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public void set(int insertedRowId, long committedAt) {
    rowId = insertedRowId;
    commitSequence = committedAt;
  }

  public void reset() {
    rowId = 0;
    commitSequence = 0;
  }
}
