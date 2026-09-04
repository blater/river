package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.BTreeSplitResult;
import java.nio.ByteBuffer;

/** Owns indexed-table B-tree traversal and split propagation state. */
final class IndexedTableIndexTree {
  private final IndexedPageSet pages;
  private final BTreeSplitResult splitResult = new BTreeSplitResult();
  private final int[] splitPathPageIds =
      new int[BTreeStructuralLimits.MAXIMUM_INTERNAL_LEVELS];
  private final IndexedTreeLookup lookup;
  private final IndexedSnapshotTreeLookup snapshotLookup;
  private final IndexedStagedPageAllocation stagedAllocation =
      new IndexedStagedPageAllocation();
  private final IndexedOperationPage logicalMetadata = new IndexedOperationPage();
  private final IndexedOperationPage logicalParent = new IndexedOperationPage();
  private final IndexedOperationPage logicalNewPage = new IndexedOperationPage();
  private int splitPromotedRightPageId;
  private boolean splitParentPromoted;
  private int splitPathDepth;

  IndexedTableIndexTree(IndexedPageSet pages) {
    this.pages = pages;
    lookup = new IndexedTreeLookup(pages, splitPathPageIds);
    snapshotLookup = new IndexedSnapshotTreeLookup(pages);
  }

  int findLeafPageId(long space, long key) {
    return findLeafPageId(space, key, false, false);
  }

  int findLeafPageIdAt(long visibleCommitSequence, long space, long key) {
    return snapshotLookup.find(visibleCommitSequence, space, key);
  }

  StatusCode snapshotLookupStatus() { return snapshotLookup.lastStatus(); }

  int findOperationLeafPageId(long space, long key) {
    return findLeafPageId(space, key, true, true);
  }

  StatusCode lookupStatus() { return lookup.lastStatus(); }

  StatusCode splitAndInsert(
      int leftPageId,
      ByteBuffer left,
      long space,
      long key,
      long rowId) {
    ByteBuffer metadata = pages.stageExisting(
        IndexedTableKernel.ROOT_META_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (metadata == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = stagedAllocation.stage(
        pages, metadata, IndexedTableLimits.MAX_CHANGED_PAGES,
        io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0);
    if (!status.isOk()) return status;
    int rightPageId = stagedAllocation.pageId();
    ByteBuffer right = stagedAllocation.payload();
    status = BTreePage.splitLeaf(
        left, right, rightPageId, space, key, rowId, splitResult);
    if (!status.isOk()) return status;
    long separator = splitResult.separatorKey();
    long separatorSpace = splitResult.separatorSpace();
    int promotedLeftPageId = leftPageId;
    int promotedRightPageId = rightPageId;
    for (int level = splitPathDepth - 1; level >= 0; level--) {
      int parentPageId = splitPathPageIds[level];
      status = promoteIntoParent(
          metadata, parentPageId, separatorSpace, separator, promotedRightPageId);
      if (!status.isOk()) return status;
      if (!splitParentPromoted) return StatusCode.OK;
      int internalRightPageId = splitPromotedRightPageId;
      separator = splitResult.separatorKey();
      separatorSpace = splitResult.separatorSpace();
      promotedLeftPageId = parentPageId;
      promotedRightPageId = internalRightPageId;
    }
    status = stagedAllocation.stage(
        pages, metadata, IndexedTableLimits.MAX_CHANGED_PAGES,
        io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0);
    if (!status.isOk()) return status;
    int newRootPageId = stagedAllocation.pageId();
    status = BTreePage.initializeInternal(stagedAllocation.payload(), promotedLeftPageId);
    if (status.isOk()) {
      status = BTreePage.insertInternal(
          stagedAllocation.payload(), separatorSpace, separator, promotedRightPageId);
    }
    if (status.isOk()) BTreeRootPage.publishRoot(metadata, newRootPageId);
    return status;
  }

  StatusCode splitAndInsertLogical(
      int leftPageId, ByteBuffer left, long space, long key, long rowId) {
    StatusCode status = pages.pinScalarOperationPage(
        IndexedTableKernel.ROOT_META_PAGE_ID, true, logicalMetadata);
    if (!status.isOk()) return status;
    status = IndexedOperationPageAllocation.scalar(
        pages, logicalMetadata.payload(), logicalNewPage);
    if (!status.isOk()) return finishLogical(status);
    int rightPageId = logicalNewPage.pageId();
    status = BTreePage.splitLeaf(
        left, logicalNewPage.payload(), rightPageId, space, key, rowId, splitResult);
    status = release(logicalNewPage, status);
    if (!status.isOk()) return finishLogical(status);
    long separator = splitResult.separatorKey();
    long separatorSpace = splitResult.separatorSpace();
    int promotedLeft = leftPageId;
    int promotedRight = rightPageId;
    for (int level = splitPathDepth - 1; level >= 0; level--) {
      int parentId = splitPathPageIds[level];
      status = pages.pinScalarOperationPage(parentId, true, logicalParent);
      if (!status.isOk()) return finishLogical(status);
      splitParentPromoted = false;
      status = BTreePage.insertInternal(
          logicalParent.payload(), separatorSpace, separator, promotedRight);
      if (status == StatusCode.RESOURCE_EXHAUSTED) {
        status = IndexedOperationPageAllocation.scalar(
            pages, logicalMetadata.payload(), logicalNewPage);
        int internalRight = logicalNewPage.pageId();
        if (status.isOk()) status = BTreePage.splitInternal(
            logicalParent.payload(), logicalNewPage.payload(), separatorSpace,
            separator, promotedRight, splitResult);
        status = release(logicalNewPage, status);
        if (status.isOk()) {
          splitParentPromoted = true;
          splitPromotedRightPageId = internalRight;
        }
      }
      status = release(logicalParent, status);
      if (!status.isOk()) return finishLogical(status);
      if (!splitParentPromoted) return finishLogical(StatusCode.OK);
      separator = splitResult.separatorKey();
      separatorSpace = splitResult.separatorSpace();
      promotedLeft = parentId;
      promotedRight = splitPromotedRightPageId;
    }
    status = IndexedOperationPageAllocation.scalar(
        pages, logicalMetadata.payload(), logicalNewPage);
    int rootId = logicalNewPage.pageId();
    if (status.isOk()) status = BTreePage.initializeInternal(
        logicalNewPage.payload(), promotedLeft);
    if (status.isOk()) status = BTreePage.insertInternal(
        logicalNewPage.payload(), separatorSpace, separator, promotedRight);
    status = release(logicalNewPage, status);
    if (status.isOk()) BTreeRootPage.publishRoot(logicalMetadata.payload(), rootId);
    return finishLogical(status);
  }

  private StatusCode finishLogical(StatusCode status) {
    status = release(logicalNewPage, status);
    status = release(logicalParent, status);
    return release(logicalMetadata, status);
  }

  private StatusCode release(IndexedOperationPage page, StatusCode status) {
    if (!page.attached()) return status;
    StatusCode released = pages.releaseOperationPage(page);
    return status.isOk() ? released : status;
  }

  private StatusCode promoteIntoParent(
      ByteBuffer metadata,
      int parentPageId,
      long separatorSpace,
      long separator,
      int promotedRightPageId) {
    splitParentPromoted = false;
    ByteBuffer parent = pages.stageExisting(
        parentPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (parent == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = BTreePage.insertInternal(
        parent, separatorSpace, separator, promotedRightPageId);
    if (status != StatusCode.RESOURCE_EXHAUSTED) return status;
    status = stagedAllocation.stage(
        pages, metadata, IndexedTableLimits.MAX_CHANGED_PAGES,
        io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE, 0);
    if (!status.isOk()) return status;
    int internalRightPageId = stagedAllocation.pageId();
    ByteBuffer internalRight = stagedAllocation.payload();
    status = BTreePage.splitInternal(
        parent, internalRight, separatorSpace, separator,
        promotedRightPageId, splitResult);
    if (status.isOk()) {
      splitPromotedRightPageId = internalRightPageId;
      splitParentPromoted = true;
    }
    return status;
  }

  private int findLeafPageId(
      long space, long key, boolean operation, boolean capturePath) {
    int pageId = lookup.find(space, key, operation, capturePath);
    splitPathDepth = lookup.pathDepth();
    return pageId;
  }
}
