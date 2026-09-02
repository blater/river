package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Builds and installs one bounded, replayable vacuum shadow generation. */
final class IndexedVacuumShadowPages {
  private final IndexedPageSet pages;
  private int heapPageId;
  private int leafPageId;
  private StatusCode lastStatus = StatusCode.OK;

  IndexedVacuumShadowPages(IndexedPageSet pageSet) {
    pages = pageSet;
  }

  StatusCode begin() {
    heapPageId = IndexedTableKernel.HEAP_PAGE_ID;
    leafPageId = 0;
    lastStatus = StatusCode.OK;
    for (int pageId = 1; lastStatus.isOk() && pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer current = pages.isPresent(pageId)
          ? pages.currentPayloadUnchecked(pageId) : null;
      if (current == null) return lastStatus = StatusCode.CORRUPTION;
      boolean heap = HeapPage.isHeap(current);
      boolean leaf = !heap
          && pageId != IndexedTableKernel.ROOT_META_PAGE_ID
          && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
          && BTreePage.type(current) == BTreePage.TYPE_LEAF;
      if (!heap && !leaf) continue;
      ByteBuffer shadow = pages.beginVacuumPage(pageId);
      lastStatus = shadow == null ? pages.lastStatus() : StatusCode.OK;
      if (lastStatus.isOk() && heap) lastStatus = HeapPage.initialize(shadow);
      if (lastStatus.isOk()) lastStatus = pages.sealVacuumPage(pageId);
    }
    return lastStatus;
  }

  ByteBuffer heap(int rowBytes) {
    ByteBuffer heap = pages.vacuumPayload(heapPageId);
    if (heap == null) return failed(pages.lastStatus());
    if (HeapPage.canInsert(heap, rowBytes)) return heap;
    lastStatus = pages.sealVacuumPage(heapPageId);
    if (!lastStatus.isOk()) return null;
    heapPageId = nextHeapPageId(heapPageId);
    heap = heapPageId == 0 ? null : pages.vacuumPayload(heapPageId);
    return heap == null ? failed(StatusCode.RESOURCE_EXHAUSTED) : heap;
  }

  ByteBuffer leaf(int pageId) {
    if (pageId != leafPageId) {
      if (leafPageId != 0) lastStatus = pages.sealVacuumPage(leafPageId);
      if (!lastStatus.isOk()) return null;
      leafPageId = pageId;
    }
    ByteBuffer leaf = pages.vacuumPayload(leafPageId);
    return leaf == null ? failed(pages.lastStatus()) : leaf;
  }

  StatusCode finish() {
    lastStatus = heapPageId == 0
        ? StatusCode.CORRUPTION : pages.sealVacuumPage(heapPageId);
    if (lastStatus.isOk() && leafPageId != 0) {
      lastStatus = pages.sealVacuumPage(leafPageId);
    }
    return lastStatus;
  }

  StatusCode publish(long start, long end) {
    for (int pageId = 1; lastStatus.isOk() && pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer payload = pages.currentPayloadUnchecked(pageId);
      if (payload == null) return lastStatus = pages.lastStatus();
      boolean publish = HeapPage.isHeap(payload)
          || pageId != IndexedTableKernel.ROOT_META_PAGE_ID
              && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
              && BTreePage.type(payload) == BTreePage.TYPE_LEAF;
      if (publish) lastStatus = pages.publishVacuumPage(pageId, start, end);
    }
    return lastStatus.isOk() ? pages.forceVacuumPublication() : lastStatus;
  }

  StatusCode lastStatus() { return lastStatus; }

  void reset() {
    pages.discardVacuumPages();
    heapPageId = 0;
    leafPageId = 0;
    lastStatus = StatusCode.OK;
  }

  private ByteBuffer failed(StatusCode status) {
    lastStatus = status;
    return null;
  }

  private int nextHeapPageId(int afterPageId) {
    for (int pageId = afterPageId + 1; pageId <= pages.highestPageId(); pageId++) {
      if (pages.isPresent(pageId) && HeapPage.isHeap(pages.currentPayloadUnchecked(pageId))) {
        return pageId;
      }
    }
    return 0;
  }
}
