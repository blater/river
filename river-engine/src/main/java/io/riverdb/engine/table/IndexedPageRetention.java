package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Fixed-capacity ownership ledger for page buffers retained by one operation. */
final class IndexedPageRetention {
  private final IndexedPageSet pages;
  private final int[] pageIds;
  private int count;

  IndexedPageRetention(IndexedPageSet pageSet, int capacity) {
    pages = pageSet;
    pageIds = new int[capacity];
  }

  StatusCode retain(int pageId) {
    StatusCode status = pages.retainBuffer(pageId);
    if (status.isOk()) pageIds[count++] = pageId;
    return status;
  }

  void release() {
    for (int index = count - 1; index >= 0; index--) {
      pages.releaseBuffer(pageIds[index]);
      pageIds[index] = 0;
    }
    count = 0;
  }
}
