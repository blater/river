package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleBTreePageHeader;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Validates one page according to its persisted payload ownership envelope. */
final class IndexedPagePayloadValidation {
  private final IndexedPageSet pages;
  private final TupleBTreePageHeader tupleHeader = new TupleBTreePageHeader();

  IndexedPagePayloadValidation(IndexedPageSet pageSet) {
    pages = pageSet;
  }

  StatusCode validate(int pageId) {
    ByteBuffer payload = pages.currentPayloadUnchecked(pageId);
    if (pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_FREE) {
      return pageId >= BTreeRootPage.FIRST_REUSABLE_PAGE_ID
              && pages.ownerKeyId(pageId) == PageCodec.SCALAR_OWNER_KEY_ID
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    if (pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_TUPLE_BTREE) {
      return CatalogKeyspace.validKeyId(pages.ownerKeyId(pageId))
          ? TupleBTreePageCodec.validateEnvelope(payload, 0, tupleHeader)
          : StatusCode.CORRUPTION;
    }
    if (pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_SCALAR_BTREE
        || pages.ownerKeyId(pageId) != PageCodec.SCALAR_OWNER_KEY_ID) {
      return StatusCode.CORRUPTION;
    }
    if (HeapPage.isHeap(payload)) return HeapPage.validate(payload);
    return pageId == IndexedTableKernel.ROOT_META_PAGE_ID
        ? BTreeRootPage.validate(payload) : BTreePage.validate(payload);
  }
}
