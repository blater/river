package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Validates pages that participate in the streaming vacuum shadow generation. */
final class IndexedVacuumPublicationAdmission {
  private final IndexedPageSet pages;

  IndexedVacuumPublicationAdmission(IndexedPageSet pageSet) {
    pages = pageSet;
  }

  StatusCode admit() {
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
        ByteBuffer payload = pages.currentPayload(pageId);
        if (payload == null) return pages.lastStatus();
        boolean heap = HeapPage.isHeap(payload);
        status = heap ? HeapPage.validate(payload)
            : pageId == IndexedTableKernel.ROOT_META_PAGE_ID
                ? BTreeRootPage.validate(payload) : BTreePage.validate(payload);
        if (!status.isOk()) return status;
      } finally {
        pages.unpinCurrentPage(pageId);
      }
    }
    return StatusCode.OK;
  }
}
