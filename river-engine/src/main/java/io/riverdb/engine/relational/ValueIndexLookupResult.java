package io.riverdb.engine.relational;

import io.riverdb.storage.heap.HeapRowResult;

/** Caller-owned primary key and borrowed row resolved through a unique value index. */
public final class ValueIndexLookupResult {
  private final HeapRowResult row = new HeapRowResult();
  private long key;
  private boolean available;

  public void reset() {
    row.reset();
    key = 0;
    available = false;
  }

  void setKey(long primaryKey) {
    key = primaryKey;
    available = true;
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
