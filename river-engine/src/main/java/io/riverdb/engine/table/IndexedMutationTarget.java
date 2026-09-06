package io.riverdb.engine.table;

/** Caller-owned resolved row-version target for a deferred update or delete. */
public final class IndexedMutationTarget {
  private long rowId;
  private long observedCommitSequence;

  long observedCommitSequence() { return observedCommitSequence; }

  void observeCommit(long sequence) { observedCommitSequence = sequence; }

  public long rowId() {
    return rowId;
  }

  public void set(long value) {
    rowId = value;
  }

  public void reset() {
    rowId = 0;
    observedCommitSequence = 0;
  }
}
