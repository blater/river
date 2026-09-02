package io.riverdb.engine.table;

/** Reusable status-bearing MVCC version lookup result. */
final class IndexedVersionRecord {
  private long commitSequence;
  private long previousRowId;
  private boolean deleted;
  private boolean available;

  void reset() {
    commitSequence = 0;
    previousRowId = 0;
    deleted = false;
    available = false;
  }

  void set(long committedAt, long previous, boolean rowDeleted) {
    commitSequence = committedAt;
    previousRowId = previous;
    deleted = rowDeleted;
    available = true;
  }

  long commitSequence() { return commitSequence; }
  long previousRowId() { return previousRowId; }
  boolean deleted() { return deleted; }
  boolean available() { return available; }
}
