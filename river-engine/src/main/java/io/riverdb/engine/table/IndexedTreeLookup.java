package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import java.nio.ByteBuffer;

/** Reusable read traversal for the indexed tree; it never allocates per lookup. */
final class IndexedTreeLookup {
  private final IndexedPageSet pages;
  private final int[] path;
  private int pathDepth;
  private StatusCode lastStatus = StatusCode.OK;

  IndexedTreeLookup(IndexedPageSet pageSet, int[] splitPath) {
    pages = pageSet;
    path = splitPath;
  }

  int pathDepth() {
    return pathDepth;
  }

  StatusCode lastStatus() { return lastStatus; }

  int find(long space, long key, boolean operation, boolean capturePath) {
    pathDepth = 0;
    lastStatus = StatusCode.OK;
    int pageId = rootPage(operation);
    if (pageId <= 0) return fail(lastStatus);
    for (int depth = 0; BTreeStructuralLimits.canVisitLevel(depth); depth++) {
      if (!BTreeStructuralLimits.validPageId(pageId)) {
        return fail(StatusCode.CORRUPTION);
      }
      if (operation) {
        ByteBuffer page = pages.operationPayload(pageId);
        if (page == null) return fail(pages.lastStatus());
        if (BTreePage.type(page) == BTreePage.TYPE_LEAF) return pageId;
        if (BTreePage.type(page) != BTreePage.TYPE_INTERNAL) {
          return fail(StatusCode.CORRUPTION);
        }
        if (!captureParent(depth, pageId, capturePath)) return 0;
        pageId = BTreePage.childForKey(page, space, key);
      } else {
        int currentPageId = pageId;
        StatusCode status = pages.pinCurrentPage(currentPageId);
        if (!status.isOk()) return fail(status);
        try {
          ByteBuffer page = pages.currentPayload(currentPageId);
          if (page == null) return fail(pages.lastStatus());
          if (BTreePage.type(page) == BTreePage.TYPE_LEAF) return pageId;
          if (BTreePage.type(page) != BTreePage.TYPE_INTERNAL) {
            return fail(StatusCode.CORRUPTION);
          }
          if (!captureParent(depth, pageId, capturePath)) return 0;
          pageId = BTreePage.childForKey(page, space, key);
        } finally {
          pages.unpinCurrentPage(currentPageId);
        }
      }
    }
    return fail(StatusCode.CORRUPTION);
  }

  private boolean captureParent(int depth, int pageId, boolean capturePath) {
    if (!BTreeStructuralLimits.canDescendFrom(depth)) {
      fail(StatusCode.CORRUPTION);
      return false;
    }
    if (!capturePath) return true;
    if (pathDepth < 0 || pathDepth >= path.length) {
      fail(StatusCode.CORRUPTION);
      return false;
    }
    path[pathDepth++] = pageId;
    return true;
  }

  private int rootPage(boolean operation) {
    if (operation) {
      ByteBuffer metadata = pages.operationPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
      return metadata == null ? fail(pages.lastStatus()) : BTreeRootPage.rootPageId(metadata);
    }
    StatusCode status = pages.pinCurrentPage(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (!status.isOk()) return fail(status);
    try {
      ByteBuffer metadata = pages.currentPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
      return metadata == null ? fail(pages.lastStatus()) : BTreeRootPage.rootPageId(metadata);
    } finally {
      pages.unpinCurrentPage(IndexedTableKernel.ROOT_META_PAGE_ID);
    }
  }

  private int fail(StatusCode status) {
    lastStatus = status.isOk() ? StatusCode.CORRUPTION : status;
    return 0;
  }
}
