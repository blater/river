package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeFreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Retains and accounts for heap pages needed by one logical row operation. */
final class IndexedLogicalRowPagePlan {
  private static final int FIXED_PAGE_RESERVATIONS = 3;

  private final IndexedPageSet pages;
  private final IndexedPageRetention retention;
  private int availableBytes;
  private int pageCapacity;
  private int nextPageId;
  private int nextFreePageId;
  private int freePages;
  private boolean active;

  IndexedLogicalRowPagePlan(IndexedPageSet pageSet) {
    pages = pageSet;
    retention = new IndexedPageRetention(
        pages, IndexedTableLimits.MAX_OPERATION_ROWS + FIXED_PAGE_RESERVATIONS);
  }

  StatusCode begin(long rowCount, int lastHeapPageId, int count) {
    if (active || count <= 0 || count > IndexedTableLimits.MAX_OPERATION_ROWS
        || rowCount > IndexedTableLimits.MAX_ROWS - count
        || !pages.operationPresentPage(IndexedTableKernel.HEAP_PAGE_ID)
        || !pages.operationPresentPage(IndexedTableKernel.ROOT_META_PAGE_ID)
        || !pages.operationPresentPage(lastHeapPageId)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    active = true;
    StatusCode status = retention.retain(IndexedTableKernel.HEAP_PAGE_ID);
    if (status.isOk() && lastHeapPageId != IndexedTableKernel.HEAP_PAGE_ID) {
      status = retention.retain(lastHeapPageId);
    }
    if (status.isOk()) status = retention.retain(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (!status.isOk()) return status;
    return readState(lastHeapPageId);
  }

  StatusCode admitRow(int rowBytes) {
    int required = HeapPage.SLOT_BYTES + rowBytes;
    if (rowBytes <= 0 || required > pageCapacity) return StatusCode.RESOURCE_EXHAUSTED;
    if (required > availableBytes) {
      StatusCode status = retainNextPage();
      if (!status.isOk()) return status;
      availableBytes = pageCapacity;
    }
    availableBytes -= required;
    return StatusCode.OK;
  }

  void release() {
    retention.release();
    active = false;
  }

  private StatusCode readState(int lastHeapPageId) {
    ByteBuffer baseHeap = pages.operationPayload(IndexedTableKernel.HEAP_PAGE_ID);
    ByteBuffer lastHeap = pages.operationPayload(lastHeapPageId);
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (!HeapPage.isHeap(baseHeap) || !HeapPage.isHeap(lastHeap)
        || !BTreeRootPage.validate(metadata).isOk()) return StatusCode.CORRUPTION;
    pageCapacity = baseHeap.limit() - HeapPage.HEADER_BYTES;
    availableBytes = HeapPage.availableBytes(lastHeap);
    nextPageId = BTreeRootPage.nextPageId(metadata);
    nextFreePageId = BTreeRootPage.freePageHead(metadata);
    freePages = BTreeRootPage.freePageCount(metadata);
    return StatusCode.OK;
  }

  private StatusCode retainNextPage() {
    boolean reusable = freePages > 0;
    int pageId = reusable ? nextFreePageId : nextPageId++;
    if (pageId <= 0 || pageId > IndexedTableLimits.MAX_PAGES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (pages.operationPresentPage(pageId) != reusable
        || reusable && pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_FREE) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = retention.retain(pageId);
    if (!status.isOk() || !reusable) return status;
    ByteBuffer free = pages.operationPayload(pageId);
    status = pages.ownerKeyId(pageId) == PageCodec.SCALAR_OWNER_KEY_ID
        ? BTreeFreePage.validate(free, pageId, nextPageId, freePages)
        : StatusCode.CORRUPTION;
    if (status.isOk()) {
      nextFreePageId = BTreeFreePage.nextPageId(free);
      freePages--;
    }
    return status;
  }
}
