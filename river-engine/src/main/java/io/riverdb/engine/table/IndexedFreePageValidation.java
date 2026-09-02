package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeFreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Proves that the free chain and all persisted FREE identities are exactly equal. */
final class IndexedFreePageValidation {
  private final IndexedPageSet pages;
  private final PagedBooleanArray visited =
      new PagedBooleanArray(IndexedTableLimits.MAX_PAGES);

  IndexedFreePageValidation(IndexedPageSet pageSet) { pages = pageSet; }

  StatusCode validate(ByteBuffer metadata, int nextPageId) {
    visited.clear();
    int remaining = BTreeRootPage.freePageCount(metadata);
    int pageId = BTreeRootPage.freePageHead(metadata);
    while (remaining > 0) {
      if (pageId < BTreeRootPage.FIRST_REUSABLE_PAGE_ID || pageId >= nextPageId
          || visited.get(pageId)) return StatusCode.CORRUPTION;
      StatusCode status = visited.reserve(pageId);
      if (!status.isOk()) return status;
      int currentPageId = pageId;
      status = pages.pinCurrentPage(currentPageId);
      if (!status.isOk()) return status;
      try {
        ByteBuffer payload = pages.currentPayload(pageId);
        if (payload == null) status = pages.lastStatus();
        else if (pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_FREE
            || pages.ownerKeyId(pageId) != PageCodec.SCALAR_OWNER_KEY_ID) {
          status = StatusCode.CORRUPTION;
        } else status = BTreeFreePage.validate(payload, pageId, nextPageId, remaining);
        if (status.isOk()) {
          visited.set(pageId, true);
          pageId = BTreeFreePage.nextPageId(payload);
        }
      } finally {
        pages.unpinCurrentPage(currentPageId);
      }
      if (!status.isOk()) return status;
      remaining--;
    }
    if (pageId != 0) return StatusCode.CORRUPTION;
    for (int candidate = BTreeRootPage.FIRST_REUSABLE_PAGE_ID;
        candidate < nextPageId; candidate++) {
      boolean free = pages.payloadKind(candidate) == PageCodec.PAYLOAD_KIND_FREE;
      if (free != visited.get(candidate)) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }
}
