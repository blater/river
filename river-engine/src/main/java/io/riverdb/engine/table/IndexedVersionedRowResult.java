package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapRowResult;

/** Reusable borrowed row bytes paired with their explicit MVCC version identity. */
final class IndexedVersionedRowResult {
  private final HeapRowResult row = new HeapRowResult();
  private long versionRowId;

  HeapRowResult row() { return row; }

  long versionRowId() { return versionRowId; }

  void set(long rowId) { versionRowId = rowId; }

  void reset() {
    row.reset();
    versionRowId = 0;
  }
}
