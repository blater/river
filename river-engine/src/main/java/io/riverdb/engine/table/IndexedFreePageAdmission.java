package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeFreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Validates the durable free-stack head before any identity is staged. */
final class IndexedFreePageAdmission {
  private IndexedFreePageAdmission() { }

  static StatusCode validate(IndexedPageSet pages, ByteBuffer metadata) {
    StatusCode status = BTreeRootPage.validate(metadata);
    if (!status.isOk()) return status;
    int count = BTreeRootPage.freePageCount(metadata);
    if (count == 0) return StatusCode.OK;
    int pageId = BTreeRootPage.freePageHead(metadata);
    if (!pages.isPresent(pageId)
        || pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_FREE
        || pages.ownerKeyId(pageId) != PageCodec.SCALAR_OWNER_KEY_ID) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer free = pages.currentPayload(pageId);
    return free == null ? pages.lastStatus()
        : BTreeFreePage.validate(
            free, pageId, BTreeRootPage.nextPageId(metadata), count);
  }
}
