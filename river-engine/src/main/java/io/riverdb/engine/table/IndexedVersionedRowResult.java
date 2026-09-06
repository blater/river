package io.riverdb.engine.table;

/** Caller-owned MVCC identity and durability dependency of one resolved row decision. */
final class IndexedVersionedRowResult {
  private long versionRowId;
  private long observedCommitSequence;

  long observedCommitSequence() { return observedCommitSequence; }

  void observeCommit(long sequence) { observedCommitSequence = sequence; }

  long versionRowId() { return versionRowId; }

  void set(long rowId) { versionRowId = rowId; }

  void reset() {
    versionRowId = 0;
    observedCommitSequence = 0;
  }
}
