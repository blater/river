package io.riverdb.engine.table;

import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Computes bounded scalar-row page capacity without mutating kernel state. */
final class IndexedKernelCapacity {
  private final IndexedPageSet pages;

  IndexedKernelCapacity(IndexedPageSet pageSet) { pages = pageSet; }

  boolean row(long rowCount, int lastHeapPageId, int rowBytes) {
    if (rowBytes <= 0
        || rowCount >= IndexedTableLimits.MAX_ROWS
        || !pages.operationPresentPage(lastHeapPageId)) return false;
    if (HeapPage.canInsert(pages.operationPayload(lastHeapPageId), rowBytes)) return true;
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    return rowBytes + HeapPage.SLOT_BYTES <= pageCapacity()
        && BTreeRootPage.nextPageId(metadata) <= IndexedTableLimits.MAX_PAGES;
  }

  int available(int lastHeapPageId) {
    return HeapPage.availableBytes(pages.operationPayload(lastHeapPageId));
  }

  private int pageCapacity() {
    return pages.operationPayload(IndexedTableKernel.HEAP_PAGE_ID).limit()
        - HeapPage.HEADER_BYTES;
  }

}
