package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Checked operation-page allocation from the intrusive free stack. */
final class IndexedOperationPageAllocation {
  private IndexedOperationPageAllocation() { }

  static StatusCode scalar(
      IndexedPageSet pages, ByteBuffer metadata, IndexedOperationPage result) {
    return allocate(pages, metadata, PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0, result);
  }

  static StatusCode tuple(
      IndexedPageSet pages, ByteBuffer metadata, long owner, IndexedOperationPage result) {
    return allocate(pages, metadata, PageCodec.PAYLOAD_KIND_TUPLE_BTREE, owner, result);
  }

  private static StatusCode allocate(
      IndexedPageSet pages, ByteBuffer metadata, int kind,
      long owner, IndexedOperationPage result) {
    StatusCode status = IndexedFreePageAdmission.validate(pages, metadata);
    if (!status.isOk()) return status;
    boolean reusable = BTreeRootPage.freePageCount(metadata) > 0;
    int pageId = BTreeRootPage.nextAllocationPage(metadata);
    int nextFree = reusable
        ? io.riverdb.storage.btree.BTreeFreePage.nextPageId(
            pages.currentPayloadUnchecked(pageId)) : -1;
    status = kind == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
        ? pages.pinNewScalarOperationPage(pageId, result)
        : pages.pinNewTupleOperationPage(pageId, owner, result);
    if (status.isOk()) status = BTreeRootPage.allocatePage(
        metadata, pageId, nextFree);
    if (!status.isOk() && result.attached()) pages.releaseOperationPage(result);
    return status;
  }
}
