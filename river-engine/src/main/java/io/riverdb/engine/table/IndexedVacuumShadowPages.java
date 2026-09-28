package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.LogicalHeadPageCodec;
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
      lastStatus = beginPage(pageId);
    }
    return lastStatus;
  }

  private StatusCode beginPage(int pageId) {
    ByteBuffer current = currentPayload(pageId);
    if (current == null) return StatusCode.CORRUPTION;
    boolean heap = HeapPage.isHeap(current);
    boolean leaf = !heap && isLeaf(pageId, current);
    if (!heap && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_LOGICAL_HEAD
        && LogicalHeadPageCodec.type(current) == LogicalHeadPageCodec.ROW_LEAF) leaf = true;
    if (!heap && !leaf) return StatusCode.OK;
    ByteBuffer shadow = pages.beginVacuumPage(pageId);
    StatusCode status = shadowStatus(shadow);
    if (status.isOk() && heap) status = HeapPage.initialize(shadow);
    return status.isOk() ? pages.sealVacuumPage(pageId) : status;
  }

  private ByteBuffer currentPayload(int pageId) {
    return pages.isPresent(pageId) ? pages.currentPayloadUnchecked(pageId) : null;
  }

  private StatusCode shadowStatus(ByteBuffer shadow) {
    return shadow == null ? pages.lastStatus() : StatusCode.OK;
  }

  private boolean isLeaf(int pageId, ByteBuffer payload) {
    return pageId != IndexedTableKernel.ROOT_META_PAGE_ID
        && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
        && BTreePage.type(payload) == BTreePage.TYPE_LEAF;
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

  StatusCode rewriteHead(int pageId, int slot, long expected, long compacted) {
    ByteBuffer head = leaf(pageId);
    if (head == null) return lastStatus;
    if (LogicalHeadPageCodec.rowHead(head, slot) != expected) {
      return lastStatus = StatusCode.CORRUPTION;
    }
    LogicalHeadPageCodec.rowHead(head, slot, -compacted);
    return StatusCode.OK;
  }

  StatusCode finish() {
    lastStatus = heapPageId == 0
        ? StatusCode.CORRUPTION : pages.sealVacuumPage(heapPageId);
    if (lastStatus.isOk() && leafPageId != 0) {
      lastStatus = pages.sealVacuumPage(leafPageId);
    }
    for (int pageId = IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID;
        lastStatus.isOk() && pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId)
          || pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_LOGICAL_HEAD) continue;
      ByteBuffer current = pages.currentPayloadUnchecked(pageId);
      if (current == null) return lastStatus = pages.lastStatus();
      if (LogicalHeadPageCodec.type(current) != LogicalHeadPageCodec.ROW_LEAF) continue;
      ByteBuffer head = pages.vacuumPayload(pageId);
      if (head == null) return lastStatus = pages.lastStatus();
      for (int slot = 0; slot < LogicalHeadPageCodec.ROW_LEAF_ENTRIES; slot++) {
        long value = LogicalHeadPageCodec.rowHead(head, slot);
        if (value < 0) {
          if (LogicalHeadPageCodec.rowHead(current, slot) <= 0) {
            return lastStatus = StatusCode.CORRUPTION;
          }
          LogicalHeadPageCodec.rowHead(head, slot, -value);
        }
      }
      lastStatus = pages.sealVacuumPage(pageId);
    }
    return lastStatus;
  }

  StatusCode publish(long start, long end) {
    for (int pageId = 1; lastStatus.isOk() && pageId <= pages.highestPageId(); pageId++) {
      ByteBuffer payload = pages.currentPayloadUnchecked(pageId);
      if (payload == null) return lastStatus = pages.lastStatus();
      boolean publish = HeapPage.isHeap(payload)
          || pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_LOGICAL_HEAD
              && LogicalHeadPageCodec.type(payload) == LogicalHeadPageCodec.ROW_LEAF
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
