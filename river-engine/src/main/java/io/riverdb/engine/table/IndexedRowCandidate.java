package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapRowResult;

/** Caller-owned visible row identity and borrowed bytes for a later lock-current operation. */
public final class IndexedRowCandidate {
  private final HeapRowResult row = new HeapRowResult();
  private IndexedTransactionSession owner;
  private long transactionGeneration;
  private long keySpace;
  private long key;
  private long versionRowId;
  private int pendingIndex = -1;

  public HeapRowResult row() { return row; }

  public long keySpace() { return keySpace; }

  public long key() { return key; }

  public long versionRowId() { return versionRowId; }

  public boolean isPending() { return pendingIndex >= 0; }

  public boolean isAvailable() { return owner != null; }

  public void reset() {
    row.reset();
    owner = null;
    transactionGeneration = 0;
    keySpace = 0;
    key = 0;
    versionRowId = 0;
    pendingIndex = -1;
  }

  void setCommitted(
      IndexedTransactionSession session, long generation,
      long space, long value, long rowId) {
    owner = session;
    transactionGeneration = generation;
    keySpace = space;
    key = value;
    versionRowId = rowId;
    pendingIndex = -1;
  }

  void setPending(
      IndexedTransactionSession session, long generation,
      long space, long value, long previousRowId, int index) {
    owner = session;
    transactionGeneration = generation;
    keySpace = space;
    key = value;
    versionRowId = previousRowId;
    pendingIndex = index;
  }

  boolean isOwnedBy(IndexedTransactionSession session, long generation) {
    return owner == session && transactionGeneration == generation;
  }

  int pendingIndex() { return pendingIndex; }
}
