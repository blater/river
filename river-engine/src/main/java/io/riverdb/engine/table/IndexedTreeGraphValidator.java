package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapPage;
import java.nio.ByteBuffer;

/** Reusable depth-first validation of B-tree links and version-chain coverage. */
final class IndexedTreeGraphValidator {
  private static final int MAXIMUM_TREE_HEIGHT = 8;
  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final IndexedVersionRecord version = new IndexedVersionRecord();
  private final PagedBooleanArray visited;
  private int previousLeafPageId;
  private int leafVersionRows;
  private int chainVersionRows;
  private long versionRows;
  private long rowCount;

  IndexedTreeGraphValidator(IndexedPageSet pageSet, IndexedVersionState versionState) {
    this(
        pageSet,
        versionState,
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES));
  }

  IndexedTreeGraphValidator(
      IndexedPageSet pageSet,
      IndexedVersionState versionState,
      PagedBooleanArray visitedPages) {
    pages = pageSet;
    versions = versionState;
    visited = visitedPages;
  }

  StatusCode validate(int rootPageId, int nextPageId, long rows) {
    rowCount = rows;
    visited.clear();
    previousLeafPageId = 0;
    versionRows = 0;
    StatusCode status = validateSubtree(
        rootPageId, 0, 0, false, OrderedKey.INFINITY_SPACE, 0, 0);
    if (!status.isOk() || versionRows != rowCount) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = validateLeafTail();
    return status.isOk() ? validateTreeCoverage(nextPageId) : status;
  }

  private StatusCode validateSubtree(
      int pageId,
      long lowerSpace,
      long lowerBound,
      boolean hasLowerBound,
      long upperSpace,
      long upperBound,
    int depth) {
    if (pageId <= 0 || pageId > IndexedTableLimits.MAX_PAGES) return StatusCode.CORRUPTION;
    StatusCode reservation = visited.reserve(pageId);
    if (!reservation.isOk()) return reservation;
    StatusCode pinStatus = pages.pinCurrentPage(pageId);
    if (!pinStatus.isOk()) return pinStatus;
    try {
      if (depth >= MAXIMUM_TREE_HEIGHT || visited.get(pageId)) return StatusCode.CORRUPTION;
      ByteBuffer page = pages.currentPayload(pageId);
      if (page == null || HeapPage.isHeap(page)
          || pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_SCALAR_BTREE) {
        return StatusCode.CORRUPTION;
      }
      visited.set(pageId, true);
      int type = BTreePage.type(page);
      int entryCount = BTreePage.entryCount(page);
      if (!OrderedKey.equal(
          BTreePage.highSpace(page), BTreePage.highKey(page), upperSpace, upperBound)) {
        return StatusCode.CORRUPTION;
      }
      if (type == BTreePage.TYPE_LEAF) {
        return validateLeaf(pageId, page, entryCount, lowerSpace, lowerBound, hasLowerBound, depth);
      }
      if (type != BTreePage.TYPE_INTERNAL || entryCount <= 0) return StatusCode.CORRUPTION;
      return validateInternal(
          page, entryCount, lowerSpace, lowerBound, hasLowerBound,
          upperSpace, upperBound, depth);
    } finally {
      pages.unpinCurrentPage(pageId);
    }
  }

  private StatusCode validateLeaf(
      int pageId, ByteBuffer page, int entryCount, long lowerSpace, long lowerBound,
      boolean hasLowerBound, int depth) {
    if (entryCount == 0) {
      return depth == 0 && rowCount == 0 ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    long firstKey = BTreePage.keyAt(page, 0);
    long firstSpace = BTreePage.spaceAt(page, 0);
    if (hasLowerBound && !OrderedKey.equal(firstSpace, firstKey, lowerSpace, lowerBound)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = validatePreviousLeaf(pageId, firstSpace, firstKey);
    if (!status.isOk()) return status;
    status = collectVersionRowsInLeaf(page);
    if (!status.isOk()) return status;
    if (versionRows > rowCount - leafVersionRows) return StatusCode.CORRUPTION;
    versionRows += leafVersionRows;
    previousLeafPageId = pageId;
    return StatusCode.OK;
  }

  private StatusCode validatePreviousLeaf(int pageId, long firstSpace, long firstKey) {
    if (previousLeafPageId <= 0) return StatusCode.OK;
    StatusCode status = pages.pinCurrentPage(previousLeafPageId);
    if (!status.isOk()) return status;
    try {
      ByteBuffer previous = pages.currentPayload(previousLeafPageId);
      if (previous == null) return pages.lastStatus();
      return BTreePage.rightSiblingPageId(previous) == pageId
              && OrderedKey.equal(
                  BTreePage.highSpace(previous),
                  BTreePage.highKey(previous),
                  firstSpace,
                  firstKey)
          ? StatusCode.OK : StatusCode.CORRUPTION;
    } finally {
      pages.unpinCurrentPage(previousLeafPageId);
    }
  }

  private StatusCode validateInternal(
      ByteBuffer page, int entryCount, long lowerSpace, long lowerBound,
      boolean hasLowerBound, long upperSpace, long upperBound, int depth) {
    int childPageId = BTreePage.firstChildPageId(page);
    long childLowerSpace = lowerSpace;
    long childLower = lowerBound;
    boolean childHasLower = hasLowerBound;
    for (int childIndex = 0; childIndex <= entryCount; childIndex++) {
      long childUpperSpace = childIndex < entryCount
          ? BTreePage.spaceAt(page, childIndex) : upperSpace;
      long childUpper = childIndex < entryCount
          ? BTreePage.keyAt(page, childIndex) : upperBound;
      StatusCode status = validateSubtree(
          childPageId, childLowerSpace, childLower, childHasLower,
          childUpperSpace, childUpper, depth + 1);
      if (!status.isOk()) return status;
      if (childIndex < entryCount) {
        childLowerSpace = childUpperSpace;
        childLower = childUpper;
        childHasLower = true;
        childPageId = BTreePage.valueAt(page, childIndex);
      }
    }
    return StatusCode.OK;
  }

  private StatusCode collectVersionRowsInLeaf(ByteBuffer leaf) {
    leafVersionRows = 0;
    for (int entry = 0; entry < BTreePage.entryCount(leaf); entry++) {
      StatusCode status = collectVersionChainRows(BTreePage.leafValueAt(leaf, entry));
      if (!status.isOk()) return status;
      if (leafVersionRows > rowCount - chainVersionRows) return StatusCode.CORRUPTION;
      leafVersionRows += chainVersionRows;
    }
    return StatusCode.OK;
  }

  private StatusCode collectVersionChainRows(long rowId) {
    chainVersionRows = 0;
    long newerCommitSequence = 0;
    while (rowId > 0) {
      if (rowId > rowCount) return StatusCode.CORRUPTION;
      StatusCode status = versions.lookup(rowId, rowCount, version);
      if (!status.isOk()) return status;
      if (version.commitSequence() <= 0
          || newerCommitSequence != 0
              && version.commitSequence() >= newerCommitSequence
          || version.previousRowId() < 0
          || version.previousRowId() >= rowId) {
        return StatusCode.CORRUPTION;
      }
      chainVersionRows++;
      newerCommitSequence = version.commitSequence();
      rowId = version.previousRowId();
    }
    return StatusCode.OK;
  }

  private StatusCode validateLeafTail() {
    if (rowCount == 0) return StatusCode.OK;
    if (previousLeafPageId <= 0) return StatusCode.CORRUPTION;
    StatusCode status = pages.pinCurrentPage(previousLeafPageId);
    if (!status.isOk()) return status;
    try {
      ByteBuffer lastLeaf = pages.currentPayload(previousLeafPageId);
      if (lastLeaf == null) return pages.lastStatus();
      return BTreePage.rightSiblingPageId(lastLeaf) == 0
              && OrderedKey.isInfinity(
                  BTreePage.highSpace(lastLeaf), BTreePage.highKey(lastLeaf))
          ? StatusCode.OK : StatusCode.CORRUPTION;
    } finally {
      pages.unpinCurrentPage(previousLeafPageId);
    }
  }

  private StatusCode validateTreeCoverage(int nextPageId) {
    for (int pageId = IndexedTableKernel.INITIAL_LEAF_PAGE_ID;
        pageId < nextPageId; pageId++) {
      StatusCode status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      try {
        ByteBuffer page = pages.currentPayload(pageId);
        if (page == null) return pages.lastStatus();
        if (pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_SCALAR_BTREE
            && !HeapPage.isHeap(page) && !visited.get(pageId)) {
          return StatusCode.CORRUPTION;
        }
      } finally {
        pages.unpinCurrentPage(pageId);
      }
    }
    return StatusCode.OK;
  }
}
