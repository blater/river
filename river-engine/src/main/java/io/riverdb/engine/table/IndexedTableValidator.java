package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Validates the complete persisted page graph and version-chain coverage. */
final class IndexedTableValidator {
  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final IndexedTreeGraphValidator tree;
  private final IndexedTupleRegistryValidation tuples;
  private final IndexedFreePageValidation freePages;
  private final IndexedPagePayloadValidation payloads;

  IndexedTableValidator(
      IndexedPageSet pageSet,
      IndexedVersionState versionState) {
    this(
        pageSet,
        versionState,
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES),
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES));
  }

  IndexedTableValidator(
      IndexedPageSet pageSet,
      IndexedVersionState versionState,
      PagedBooleanArray scalarVisited,
      PagedBooleanArray tupleVisited) {
    pages = pageSet;
    versions = versionState;
    tree = new IndexedTreeGraphValidator(pages, versions, scalarVisited);
    tuples = new IndexedTupleRegistryValidation(pages, versions, tupleVisited);
    freePages = new IndexedFreePageValidation(pages);
    payloads = new IndexedPagePayloadValidation(pages);
  }

  StatusCode validate(long rows) {
    ByteBuffer heap = pages.currentPayload(IndexedTableKernel.HEAP_PAGE_ID);
    ByteBuffer metadata = pages.currentPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (heap == null || metadata == null) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = HeapPage.validate(heap);
    if (status.isOk()) {
      status = BTreeRootPage.validate(metadata);
    }
    if (!status.isOk()) {
      return status;
    }
    StatusCode pinStatus = pages.pinCurrentPage(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (!pinStatus.isOk()) return pinStatus;
    try {
      int nextPageId = BTreeRootPage.nextPageId(metadata);
      if (nextPageId > IndexedTableLimits.MAX_PAGES + 1) {
        return StatusCode.CORRUPTION;
      }
      status = validatePresentPages(nextPageId);
      if (!status.isOk()) {
        return status;
      }
      status = freePages.validate(metadata, nextPageId);
      if (!status.isOk()) return status;
      int rootPageId = BTreeRootPage.rootPageId(metadata);
      if (rootPageId < IndexedTableKernel.INITIAL_LEAF_PAGE_ID
          || rootPageId >= nextPageId) {
        return StatusCode.CORRUPTION;
      }
      status = tree.validate(rootPageId, nextPageId, rows);
      return status.isOk() ? tuples.validate(nextPageId, rows) : status;
    } finally {
      pages.unpinCurrentPage(IndexedTableKernel.ROOT_META_PAGE_ID);
    }
  }

  StatusCode validateCurrentPage(int pageId) {
    return payloads.validate(pageId);
  }

  StatusCode validateAppliedPages(int[] pageIds, int pageCount) {
    for (int index = 0; index < pageCount; index++) {
      StatusCode status = validateCurrentPage(pageIds[index]);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validatePresentPages(int nextPageId) {
    for (int pageId = IndexedTableKernel.INITIAL_LEAF_PAGE_ID;
        pageId < nextPageId;
        pageId++) {
      ByteBuffer page = pages.currentPayload(pageId);
      if (page == null) {
        return StatusCode.CORRUPTION;
      }
      StatusCode status = payloads.validate(pageId);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

}
