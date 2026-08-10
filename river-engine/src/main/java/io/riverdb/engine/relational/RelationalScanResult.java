package io.riverdb.engine.relational;

import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.storage.heap.HeapRowResult;

/** Caller-owned logical primary key plus borrowed row returned by a table scan. */
public final class RelationalScanResult {
  private final IndexedScanResult indexed = new IndexedScanResult();
  private long key;
  private boolean available;

  public void reset() {
    indexed.reset();
    key = 0;
    available = false;
  }

  IndexedScanResult indexed() {
    return indexed;
  }

  void set(long userKey) {
    key = userKey;
    available = true;
  }

  public long key() {
    return key;
  }

  public HeapRowResult row() {
    return indexed.row();
  }

  public boolean isAvailable() {
    return available;
  }
}
