package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Deterministically re-identifies one bounded batch of detached tuple pages. */
final class IndexedTupleGraphReclaimer {
  private static final int REGISTRY_PUBLICATION_RESERVE = 12;
  static final int MAX_INSPECTED_PAGES = 16;
  private final IndexedPageSet pages;
  private int reclaimed;

  IndexedTupleGraphReclaimer(IndexedPageSet pageSet) { pages = pageSet; }

  StatusCode reclaimBatch(
      long keyId, int expectedCursor, int resultingCursor, int cleanupEnd) {
    ByteBuffer current = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (current == null || !BTreeRootPage.validate(current).isOk()) {
      return StatusCode.CORRUPTION;
    }
    int next = BTreeRootPage.nextPageId(current);
    if (expectedCursor < BTreeRootPage.FIRST_REUSABLE_PAGE_ID
        || expectedCursor >= next
        || cleanupEnd < expectedCursor || cleanupEnd > next
        || resultingCursor != Math.min(
            cleanupEnd, expectedCursor + MAX_INSPECTED_PAGES)) {
      return StatusCode.CORRUPTION;
    }
    int metadataPage = pages.isStaged(IndexedTableKernel.ROOT_META_PAGE_ID) ? 0 : 1;
    int budget = IndexedTableLimits.MAX_CHANGED_PAGES - pages.changedPageCount()
        - metadataPage - REGISTRY_PUBLICATION_RESERVE;
    if (budget <= 0) return StatusCode.RESOURCE_EXHAUSTED;
    int owned = ownedPages(keyId, expectedCursor, resultingCursor);
    if (owned > budget) return StatusCode.RESOURCE_EXHAUSTED;
    if (owned == 0) return StatusCode.OK;
    ByteBuffer metadata = pages.stageExisting(
        IndexedTableKernel.ROOT_META_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (metadata == null) return pages.lastStatus();
    return reclaim(keyId, metadata, expectedCursor, resultingCursor);
  }

  StatusCode finish(long keyId, int expectedCursor, int cleanupEnd) {
    ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (metadata == null || !BTreeRootPage.validate(metadata).isOk()) {
      return StatusCode.CORRUPTION;
    }
    int nextPage = BTreeRootPage.nextPageId(metadata);
    if (expectedCursor != cleanupEnd || cleanupEnd > nextPage) {
      return StatusCode.CONFLICT;
    }
    for (int pageId = cleanupEnd; pageId < nextPage; pageId++) {
      if (owned(pageId, keyId)) return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private StatusCode reclaim(
      long keyId, ByteBuffer metadata, int expectedCursor, int resultingCursor) {
    reclaimed = 0;
    for (int pageId = expectedCursor; pageId < resultingCursor; pageId++) {
      if (!owned(pageId, keyId)) continue;
      ByteBuffer free = pages.stageFreeTuple(
          pageId, keyId, IndexedTableLimits.MAX_CHANGED_PAGES);
      if (free == null) return pages.lastStatus();
      StatusCode status = BTreeRootPage.releasePage(metadata, pageId, free);
      if (!status.isOk()) return status;
      reclaimed++;
    }
    return StatusCode.OK;
  }

  private int ownedPages(long keyId, int first, int end) {
    int count = 0;
    for (int pageId = first; pageId < end; pageId++) {
      if (owned(pageId, keyId)) count++;
    }
    return count;
  }

  private boolean owned(int pageId, long keyId) {
    return pages.isPresent(pageId)
        && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        && pages.ownerKeyId(pageId) == keyId;
  }
}
