package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.BTreeSplitResult;
import java.nio.ByteBuffer;

/** Owns indexed-table B-tree traversal and split propagation state. */
final class IndexedTableIndexTree {
  private static final int MAXIMUM_TREE_HEIGHT = 8;
  private final IndexedPageSet pages;
  private final BTreeSplitResult splitResult = new BTreeSplitResult();
  private final int[] splitPathPageIds = new int[MAXIMUM_TREE_HEIGHT];
  private int splitPromotedRightPageId;
  private boolean splitParentPromoted;
  private int splitPathDepth;

  IndexedTableIndexTree(IndexedPageSet pages) {
    this.pages = pages;
  }

  int findLeafPageId(int space, long key) {
    return findLeafPageId(space, key, false, false);
  }

  int findOperationLeafPageId(int space, long key) {
    return findLeafPageId(space, key, true, true);
  }

  StatusCode splitAndInsert(
      int leftPageId,
      ByteBuffer left,
      int space,
      long key,
      int rowId) {
    ByteBuffer metadata = pages.stageExisting(
        IndexedTableKernel.ROOT_META_PAGE_ID, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (metadata == null) return StatusCode.RESOURCE_EXHAUSTED;
    int rightPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer right = pages.stageNew(rightPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (right == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = BTreePage.splitLeaf(
        left, right, rightPageId, space, key, rowId, splitResult);
    if (!status.isOk()) return status;
    long separator = splitResult.separatorKey();
    int separatorSpace = splitResult.separatorSpace();
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
    int newRootPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer root = pages.stageNew(newRootPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (root == null) return StatusCode.RESOURCE_EXHAUSTED;
    status = BTreePage.initializeInternal(root, promotedLeftPageId);
    if (status.isOk()) {
      status = BTreePage.insertInternal(
          root, separatorSpace, separator, promotedRightPageId);
    }
    if (status.isOk()) BTreeRootPage.publishRoot(metadata, newRootPageId);
    return status;
  }

  private StatusCode promoteIntoParent(
      ByteBuffer metadata,
      int parentPageId,
      int separatorSpace,
      long separator,
      int promotedRightPageId) {
    splitParentPromoted = false;
    ByteBuffer parent = pages.stageExisting(
        parentPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (parent == null) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = BTreePage.insertInternal(
        parent, separatorSpace, separator, promotedRightPageId);
    if (status != StatusCode.RESOURCE_EXHAUSTED) return status;
    int internalRightPageId = BTreeRootPage.allocatePage(metadata);
    ByteBuffer internalRight = pages.stageNew(
        internalRightPageId, IndexedTableLimits.MAX_CHANGED_PAGES);
    if (internalRight == null) return StatusCode.RESOURCE_EXHAUSTED;
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
      int space, long key, boolean operation, boolean capturePath) {
    splitPathDepth = 0;
    ByteBuffer metadata = operation
        ? pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID)
        : pages.currentPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (metadata == null) return 0;
    int pageId = BTreeRootPage.rootPageId(metadata);
    for (int depth = 0; depth < MAXIMUM_TREE_HEIGHT; depth++) {
      ByteBuffer page = operation
          ? pages.operationPayload(pageId) : pages.currentPayload(pageId);
      if (page == null) return 0;
      if (BTreePage.type(page) == BTreePage.TYPE_LEAF) return pageId;
      if (BTreePage.type(page) != BTreePage.TYPE_INTERNAL) return 0;
      if (capturePath) splitPathPageIds[splitPathDepth++] = pageId;
      pageId = BTreePage.childForKey(page, space, key);
    }
    return 0;
  }
}
