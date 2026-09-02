package io.riverdb.engine.table;

import io.riverdb.storage.heap.HeapRowResult;

/** Caller-owned token retaining one heap page behind a borrowed row view. */
final class IndexedRowPin {
  private int pageId;
  private HeapRowResult row;

  void attach(int value, HeapRowResult valueRow) {
    pageId = value;
    row = valueRow;
  }

  void reset() {
    row.reset();
    row = null;
    pageId = 0;
  }

  boolean attached() { return pageId > 0; }
  int pageId() { return pageId; }
}
