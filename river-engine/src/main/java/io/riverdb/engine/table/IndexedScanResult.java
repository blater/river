package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapRowResult;

/** Caller-owned key plus borrowed visible heap row returned by an indexed scan. */
public final class IndexedScanResult {
  private final HeapRowResult row = new HeapRowResult();
  private long key;
  private boolean available;

  public void reset() {
    key = 0;
    row.reset();
    available = false;
  }

  void set(long visibleKey) {
    key = visibleKey;
    available = true;
  }

  void copyFrom(IndexedScanResult source) {
    key = source.key;
    row.copyFrom(source.row);
    available = source.available;
  }

  public long key() {
    return key;
  }

  public HeapRowResult row() {
    return row;
  }

  public boolean isAvailable() {
    return available;
  }
}
