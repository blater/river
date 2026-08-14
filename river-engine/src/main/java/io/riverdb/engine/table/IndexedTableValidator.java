package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Validates the complete persisted page graph and version-chain coverage. */
final class IndexedTableValidator {
  private static final int MAXIMUM_TREE_HEIGHT = 8;

  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final boolean[] visited = new boolean[IndexedTableLimits.MAX_PAGES + 1];
  private int previousLeafPageId;
  private int versionRows;
  private int rowCount;

  IndexedTableValidator(
      IndexedPageSet pageSet,
      IndexedVersionState versionState) {
    pages = pageSet;
    versions = versionState;
  }

  StatusCode validate(int rows) {
    rowCount = rows;
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
    int nextPageId = BTreeRootPage.nextPageId(metadata);
    if (nextPageId > IndexedTableLimits.MAX_PAGES + 1) {
      return StatusCode.CORRUPTION;
    }
    status = validatePresentPages(nextPageId);
    if (!status.isOk()) {
      return status;
    }
    int rootPageId = BTreeRootPage.rootPageId(metadata);
    if (rootPageId < IndexedTableKernel.INITIAL_LEAF_PAGE_ID
        || rootPageId >= nextPageId) {
      return StatusCode.CORRUPTION;
    }
    resetTraversal();
    status = validateSubtree(rootPageId, 0, false, Long.MAX_VALUE, 0);
    if (!status.isOk() || versionRows != rowCount) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = validateLeafTail();
    return status.isOk() ? validateTreeCoverage(nextPageId) : status;
  }

  StatusCode validateCurrentPage(int pageId) {
    ByteBuffer payload = pages.currentPayloadUnchecked(pageId);
    if (HeapPage.isHeap(payload)) {
      return HeapPage.validate(payload);
    }
    return pageId == IndexedTableKernel.ROOT_META_PAGE_ID
        ? BTreeRootPage.validate(payload) : BTreePage.validate(payload);
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
      StatusCode status = HeapPage.isHeap(page)
          ? HeapPage.validate(page) : BTreePage.validate(page);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private void resetTraversal() {
    for (int pageId = 0; pageId < visited.length; pageId++) {
      visited[pageId] = false;
    }
    previousLeafPageId = 0;
    versionRows = 0;
  }

  private StatusCode validateSubtree(
      int pageId,
      long lowerBound,
      boolean hasLowerBound,
      long upperBound,
      int depth) {
    if (pageId <= 0
        || pageId > IndexedTableLimits.MAX_PAGES
        || depth >= MAXIMUM_TREE_HEIGHT
        || visited[pageId]) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer page = pages.currentPayload(pageId);
    if (page == null || HeapPage.isHeap(page)) {
      return StatusCode.CORRUPTION;
    }
    visited[pageId] = true;
    int type = BTreePage.type(page);
    int entryCount = BTreePage.entryCount(page);
    if (BTreePage.highKey(page) != upperBound) {
      return StatusCode.CORRUPTION;
    }
    if (type == BTreePage.TYPE_LEAF) {
      return validateLeaf(pageId, page, entryCount, lowerBound, hasLowerBound, depth);
    }
    if (type != BTreePage.TYPE_INTERNAL || entryCount <= 0) {
      return StatusCode.CORRUPTION;
    }
    return validateInternal(
        page, entryCount, lowerBound, hasLowerBound, upperBound, depth);
  }

  private StatusCode validateLeaf(
      int pageId,
      ByteBuffer page,
      int entryCount,
      long lowerBound,
      boolean hasLowerBound,
      int depth) {
    if (entryCount == 0) {
      return depth == 0 && rowCount == 0
          ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    long firstKey = BTreePage.keyAt(page, 0);
    if (hasLowerBound && firstKey != lowerBound) {
      return StatusCode.CORRUPTION;
    }
    if (!validPreviousLeaf(pageId, firstKey)) {
      return StatusCode.CORRUPTION;
    }
    int leafVersions = versionRowsInLeaf(page);
    if (leafVersions < 0 || versionRows > rowCount - leafVersions) {
      return StatusCode.CORRUPTION;
    }
    versionRows += leafVersions;
    previousLeafPageId = pageId;
    return StatusCode.OK;
  }

  private boolean validPreviousLeaf(int pageId, long firstKey) {
    if (previousLeafPageId <= 0) {
      return true;
    }
    ByteBuffer previous = pages.currentPayload(previousLeafPageId);
    return BTreePage.rightSiblingPageId(previous) == pageId
        && BTreePage.highKey(previous) == firstKey;
  }

  private StatusCode validateInternal(
      ByteBuffer page,
      int entryCount,
      long lowerBound,
      boolean hasLowerBound,
      long upperBound,
      int depth) {
    int childPageId = BTreePage.firstChildPageId(page);
    long childLower = lowerBound;
    boolean childHasLower = hasLowerBound;
    for (int childIndex = 0; childIndex <= entryCount; childIndex++) {
      long childUpper = childIndex < entryCount
          ? BTreePage.keyAt(page, childIndex) : upperBound;
      StatusCode status = validateSubtree(
          childPageId, childLower, childHasLower, childUpper, depth + 1);
      if (!status.isOk()) {
        return status;
      }
      if (childIndex < entryCount) {
        childLower = childUpper;
        childHasLower = true;
        childPageId = BTreePage.valueAt(page, childIndex);
      }
    }
    return StatusCode.OK;
  }

  private int versionRowsInLeaf(ByteBuffer leaf) {
    int rows = 0;
    int entryCount = BTreePage.entryCount(leaf);
    for (int entry = 0; entry < entryCount; entry++) {
      int chainRows = versionChainRows(BTreePage.valueAt(leaf, entry));
      if (chainRows < 0 || rows > rowCount - chainRows) {
        return -1;
      }
      rows += chainRows;
    }
    return rows;
  }

  private int versionChainRows(int rowId) {
    int rows = 0;
    long newerCommitSequence = 0;
    while (rowId > 0) {
      long commitSequence = versions.commitSequence(rowId, rowCount);
      if (rowId > rowCount
          || commitSequence <= 0
          || newerCommitSequence != 0 && commitSequence >= newerCommitSequence) {
        return -1;
      }
      int previousRowId = versions.previousRow(rowId, rowCount);
      if (previousRowId < 0 || previousRowId >= rowId) {
        return -1;
      }
      rows++;
      newerCommitSequence = commitSequence;
      rowId = previousRowId;
    }
    return rows;
  }

  private StatusCode validateLeafTail() {
    if (rowCount == 0) {
      return StatusCode.OK;
    }
    if (previousLeafPageId <= 0) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer lastLeaf = pages.currentPayload(previousLeafPageId);
    return BTreePage.rightSiblingPageId(lastLeaf) == 0
            && BTreePage.highKey(lastLeaf) == Long.MAX_VALUE
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode validateTreeCoverage(int nextPageId) {
    for (int pageId = IndexedTableKernel.INITIAL_LEAF_PAGE_ID;
        pageId < nextPageId;
        pageId++) {
      ByteBuffer page = pages.currentPayload(pageId);
      if (!HeapPage.isHeap(page) && !visited[pageId]) {
        return StatusCode.CORRUPTION;
      }
    }
    return StatusCode.OK;
  }
}
