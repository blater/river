package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Reusable result for one page-image allocation from free or fresh space. */
final class IndexedStagedPageAllocation {
  private ByteBuffer payload;
  private int pageId;

  StatusCode stage(
      IndexedPageSet pages, ByteBuffer metadata, int maximum,
      int payloadKind, long ownerKeyId) {
    payload = null;
    pageId = 0;
    StatusCode status = IndexedFreePageAdmission.validate(pages, metadata);
    if (!status.isOk()) return status;
    boolean reusable = BTreeRootPage.freePageCount(metadata) > 0;
    int candidate = BTreeRootPage.nextAllocationPage(metadata);
    int nextFree = reusable
        ? io.riverdb.storage.btree.BTreeFreePage.nextPageId(
            pages.currentPayloadUnchecked(candidate)) : -1;
    ByteBuffer staged = pages.stageNew(
        candidate, maximum, payloadKind, ownerKeyId);
    if (staged == null) return pages.lastStatus();
    status = BTreeRootPage.allocatePage(
        metadata, candidate, nextFree);
    if (status.isOk()) {
      pageId = candidate;
      payload = staged;
    }
    return status;
  }

  int pageId() { return pageId; }
  ByteBuffer payload() { return payload; }
}
