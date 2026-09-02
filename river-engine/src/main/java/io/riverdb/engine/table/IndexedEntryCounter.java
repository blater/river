package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Counts scalar leaf entries while preserving page-load and validation status. */
final class IndexedEntryCounter {
  private final IndexedPageSet pages;

  IndexedEntryCounter(IndexedPageSet pageSet) {
    pages = pageSet;
  }

  StatusCode count(IndexedCountResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    long entries = 0;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isPresent(pageId)
          || pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
          || pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_FREE) continue;
      if (pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_SCALAR_BTREE
          || pages.ownerKeyId(pageId) != PageCodec.SCALAR_OWNER_KEY_ID) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      try {
        ByteBuffer page = pages.currentPayload(pageId);
        if (page == null) return pages.lastStatus();
        if (pageId == IndexedTableKernel.ROOT_META_PAGE_ID || HeapPage.isHeap(page)) continue;
        status = BTreePage.validate(page);
        if (!status.isOk()) return status;
        int type = BTreePage.type(page);
        if (type == BTreePage.TYPE_LEAF) entries += BTreePage.entryCount(page);
        else if (type != BTreePage.TYPE_INTERNAL) return StatusCode.CORRUPTION;
      } finally {
        pages.unpinCurrentPage(pageId);
      }
    }
    result.set(entries);
    return StatusCode.OK;
  }
}
