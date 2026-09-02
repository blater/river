package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapRowResult;

/** Caller-owned key plus borrowed visible heap row returned by an indexed scan. */
public final class IndexedScanResult {
  private final HeapRowResult row = new HeapRowResult();
  private long keySpace;
  private long key;
  private long versionRowId;
  private int pendingIndex = -1;
  private IndexedTransactionSession owner;
  private long transactionGeneration;
  private boolean available;

  public void reset() {
    key = 0;
    keySpace = 0;
    versionRowId = 0;
    pendingIndex = -1;
    owner = null;
    transactionGeneration = 0;
    row.reset();
    available = false;
  }

  void setCommitted(long visibleSpace, long visibleKey, long rowId) {
    keySpace = visibleSpace;
    key = visibleKey;
    versionRowId = rowId;
    pendingIndex = -1;
    available = true;
  }

  void setPending(
      IndexedTransactionSession session, long generation,
      long visibleSpace, long visibleKey, long previousRowId, int index) {
    owner = session;
    transactionGeneration = generation;
    keySpace = visibleSpace;
    key = visibleKey;
    versionRowId = previousRowId;
    pendingIndex = index;
    available = true;
  }

  void bind(IndexedTransactionSession session, long generation) {
    owner = session;
    transactionGeneration = generation;
  }

  void copyFrom(IndexedScanResult source) {
    keySpace = source.keySpace;
    key = source.key;
    versionRowId = source.versionRowId;
    pendingIndex = source.pendingIndex;
    owner = source.owner;
    transactionGeneration = source.transactionGeneration;
    row.copyFrom(source.row);
    available = source.available;
  }

  public long key() {
    return key;
  }

  public long keySpace() {
    return keySpace;
  }

  public HeapRowResult row() {
    return row;
  }

  public boolean isAvailable() {
    return available;
  }

  public long versionRowId() { return versionRowId; }

  public boolean isPending() { return pendingIndex >= 0; }

  boolean isOwnedBy(IndexedTransactionSession session, long generation) {
    return available && owner == session && transactionGeneration == generation;
  }

  int pendingIndex() { return pendingIndex; }
}
