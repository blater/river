package io.riverdb.engine.table;

/** Reusable result for one committed tuple user-key prefix probe. */
public final class IndexedTupleProbeResult {
  private boolean found;
  private long logicalRowId;
  private long observedCommitSequence;

  long observedCommitSequence() { return observedCommitSequence; }

  void observeCommit(long sequence) { observedCommitSequence = sequence; }

  public void reset() {
    found = false;
    logicalRowId = 0;
    observedCommitSequence = 0;
  }

  public boolean found() { return found; }
  public long logicalRowId() { return logicalRowId; }

  void set(long rowId) {
    found = true;
    logicalRowId = rowId;
  }
}
