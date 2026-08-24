package io.riverdb.engine.table;

/** Caller-owned row identity and commit CSN from one indexed transaction write. */
public final class IndexedCommitResult {
  private long rowId;
  private long commitSequence;

  public long rowId() {
    return rowId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public void set(long insertedRowId, long committedAt) {
    rowId = insertedRowId;
    commitSequence = committedAt;
  }

  public void reset() {
    rowId = 0;
    commitSequence = 0;
  }
}
