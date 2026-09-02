package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTreePageProvider;
import io.riverdb.storage.btree.TupleBTreePageReference;

/** Read-only tuple provider that rejects wrong ownership and repeated graph pages. */
final class IndexedTupleValidationProvider implements TupleBTreePageProvider {
  private final IndexedPageSet pages;
  private final PagedBooleanArray visited;
  private long ownerKeyId;
  private int rootPageId;
  private int nextPageId;

  IndexedTupleValidationProvider(IndexedPageSet pageSet) {
    this(pageSet, new PagedBooleanArray(IndexedTableLimits.MAX_PAGES));
  }

  IndexedTupleValidationProvider(IndexedPageSet pageSet, PagedBooleanArray visitedPages) {
    pages = pageSet;
    visited = visitedPages;
  }

  StatusCode configure(int root, long owner, int next) {
    if (root <= 0 || root >= next || !CatalogKeyspace.validKeyId(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    rootPageId = root;
    ownerKeyId = owner;
    nextPageId = next;
    return StatusCode.OK;
  }

  void reset() {
    visited.clear(); rootPageId = 0; ownerKeyId = 0; nextPageId = 0;
  }
  boolean reached(int pageId) { return visited.get(pageId); }

  StatusCode accountDetached(long owner, int next) {
    if (!CatalogKeyspace.validKeyId(owner) || next <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int pageId = 1; pageId < next; pageId++) {
      if (pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_TUPLE_BTREE
          || pages.ownerKeyId(pageId) != owner) continue;
      StatusCode status = visited.reserve(pageId);
      if (!status.isOk()) return status;
      if (visited.get(pageId)) return StatusCode.CORRUPTION;
      visited.set(pageId, true);
    }
    return StatusCode.OK;
  }
  @Override public int rootPageId() { return rootPageId; }

  @Override
  public StatusCode visit(int pageId) {
    if (pageId <= 0 || pageId >= nextPageId) return StatusCode.CORRUPTION;
    StatusCode status = visited.reserve(pageId);
    if (!status.isOk()) return status;
    if (visited.get(pageId)) return StatusCode.CORRUPTION;
    visited.set(pageId, true);
    return StatusCode.OK;
  }

  @Override
  public StatusCode pin(int pageId, boolean writable, TupleBTreePageReference result) {
    if (writable || result == null || result.isAttached()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (pageId <= 0 || pageId >= nextPageId) return StatusCode.CORRUPTION;
    StatusCode status = visited.reserve(pageId);
    if (!status.isOk()) return status;
    status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    java.nio.ByteBuffer payload = pages.currentPayload(pageId);
    if (payload == null) status = pages.lastStatus();
    else if (pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_TUPLE_BTREE
        || pages.ownerKeyId(pageId) != ownerKeyId) status = StatusCode.CORRUPTION;
    else status = result.attach(pageId, payload, 0, false, 1);
    if (!status.isOk()) { result.reset(); pages.unpinCurrentPage(pageId); }
    return status;
  }

  @Override
  public boolean pageValidationMatches(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int expectedType) {
    return false;
  }

  @Override
  public void rememberPageValidation(
      TupleBTreePageReference reference, long schemaId,
      long descriptorHash, int pageType) { }

  @Override public StatusCode allocate(TupleBTreePageReference result) {
    return StatusCode.FEATURE_NOT_SUPPORTED;
  }
  @Override public StatusCode replaceRoot(int expected, int replacement) {
    return StatusCode.FEATURE_NOT_SUPPORTED;
  }
  @Override public StatusCode release(TupleBTreePageReference reference) {
    if (reference == null || !reference.isAttached()) return StatusCode.INVALID_EXTERNAL_INPUT;
    int pageId = reference.pageId();
    reference.reset();
    pages.unpinCurrentPage(pageId);
    return StatusCode.OK;
  }
}
