package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.LogicalHeadPageCodec;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;

/** Resolves or stages a table's root entry in the fixed sparse table map. */
final class IndexedHeadTableRootMap {
  private static final int CACHE_SLOTS = 256;
  private final IndexedPageSet pages;
  private final long[] cachedTableIds = new long[CACHE_SLOTS];
  private final int[] cachedLeafPageIds = new int[CACHE_SLOTS];
  private final IndexedOperationPage metadataPin = new IndexedOperationPage();
  private final IndexedOperationPage parentPin = new IndexedOperationPage();
  private final IndexedOperationPage newPin = new IndexedOperationPage();

  IndexedHeadTableRootMap(IndexedPageSet pageSet) {
    pages = pageSet;
  }

  StatusCode nextTableAtOrAfter(long minimumTableId, IndexedHeadTableRoot result) {
    if (!CatalogKeyspace.validObjectHead(minimumTableId) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long firstLeafOrdinal = (minimumTableId - 1)
        / LogicalHeadPageCodec.TABLE_LEAF_ENTRIES;
    int firstRootSlot = (int) (firstLeafOrdinal
        / LogicalHeadPageCodec.BRANCH_ENTRIES);
    for (int rootSlot = firstRootSlot;
        rootSlot < LogicalHeadPageCodec.BRANCH_ENTRIES; rootSlot++) {
      int branchPageId;
      StatusCode status = pages.pinCurrentPage(IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID);
      if (!status.isOk()) return status;
      ByteBuffer root = pages.currentPayload(IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID);
      status = validCurrentPage(
          IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID, root,
          LogicalHeadPageCodec.TABLE_BRANCH, 0,
          LogicalHeadPageCodec.TABLE_ROOT_LEVEL);
      branchPageId = status.isOk()
          ? LogicalHeadPageCodec.branchChild(root, rootSlot) : 0;
      pages.unpinCurrentPage(IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID);
      if (!status.isOk()) return status;
      if (branchPageId < 0) return StatusCode.CORRUPTION;
      if (branchPageId == 0) continue;
      int firstBranchSlot = rootSlot == firstRootSlot
          ? (int) (firstLeafOrdinal % LogicalHeadPageCodec.BRANCH_ENTRIES) : 0;
      for (int branchSlot = firstBranchSlot;
          branchSlot < LogicalHeadPageCodec.BRANCH_ENTRIES; branchSlot++) {
        long leafOrdinal = (long) rootSlot * LogicalHeadPageCodec.BRANCH_ENTRIES
            + branchSlot;
        long firstTableId = leafOrdinal * LogicalHeadPageCodec.TABLE_LEAF_ENTRIES + 1;
        if (firstTableId > CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID) {
          return StatusCode.CONFLICT;
        }
        status = pages.pinCurrentPage(branchPageId);
        if (!status.isOk()) return status;
        ByteBuffer branch = pages.currentPayload(branchPageId);
        status = validCurrentPage(
            branchPageId, branch, LogicalHeadPageCodec.TABLE_BRANCH,
            (long) rootSlot * LogicalHeadPageCodec.BRANCH_ENTRIES, 1);
        int leafPageId = status.isOk()
            ? LogicalHeadPageCodec.branchChild(branch, branchSlot) : 0;
        pages.unpinCurrentPage(branchPageId);
        if (!status.isOk()) return status;
        if (leafPageId < 0) return StatusCode.CORRUPTION;
        if (leafPageId == 0) continue;
        status = pages.pinCurrentPage(leafPageId);
        if (!status.isOk()) return status;
        ByteBuffer leaf = pages.currentPayload(leafPageId);
        status = validCurrentPage(
            leafPageId, leaf, LogicalHeadPageCodec.TABLE_LEAF, leafOrdinal, 0);
        if (!status.isOk()) {
          pages.unpinCurrentPage(leafPageId);
          return status;
        }
        int firstSlot = leafOrdinal == firstLeafOrdinal
            ? (int) ((minimumTableId - 1) % LogicalHeadPageCodec.TABLE_LEAF_ENTRIES)
            : 0;
        for (int slot = firstSlot; slot < LogicalHeadPageCodec.TABLE_LEAF_ENTRIES;
            slot++) {
          long tableId = firstTableId + slot;
          if (tableId > CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID) break;
          int rootPageId = LogicalHeadPageCodec.tableRootPageId(leaf, slot);
          if (rootPageId == 0) continue;
          result.set(leafPageId, slot, rootPageId,
              LogicalHeadPageCodec.tableRootLevel(leaf, slot));
          result.tableId(tableId);
          status = validRoot(result) ? StatusCode.OK : StatusCode.CORRUPTION;
          pages.unpinCurrentPage(leafPageId);
          return status;
        }
        pages.unpinCurrentPage(leafPageId);
      }
    }
    return StatusCode.CONFLICT;
  }

  StatusCode load(long tableId, IndexedHeadTableRoot result) {
    if (!CatalogKeyspace.validObjectHead(tableId) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long index = tableId - 1;
    long leafOrdinal = index / LogicalHeadPageCodec.TABLE_LEAF_ENTRIES;
    int cachedLeafPageId = cachedLeafPageIds[cacheSlot(tableId)];
    if (cachedLeafPageId != 0
        && cachedTableIds[cacheSlot(tableId)] == tableId) {
      return loadLeaf(cachedLeafPageId, leafOrdinal, index, result);
    }
    int pageId = IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID;
    long base = 0;
    for (int level = LogicalHeadPageCodec.TABLE_ROOT_LEVEL; level > 0; level--) {
      int slot = branchSlot(leafOrdinal, level);
      StatusCode status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      ByteBuffer page = pages.currentPayload(pageId);
      status = validCurrentPage(
          pageId, page, LogicalHeadPageCodec.TABLE_BRANCH, base, level);
      int child = status.isOk() ? LogicalHeadPageCodec.branchChild(page, slot) : 0;
      pages.unpinCurrentPage(pageId);
      if (!status.isOk()) return status;
      if (child < 0) return StatusCode.CORRUPTION;
      if (child == 0) return StatusCode.OK;
      pageId = child;
      base += (long) slot * tableSpan(level);
    }
    StatusCode status = loadLeaf(pageId, leafOrdinal, index, result);
    if (status.isOk()) cache(tableId, pageId);
    return status;
  }

  private StatusCode loadLeaf(
      int pageId, long leafOrdinal, long index, IndexedHeadTableRoot result) {
    StatusCode status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    ByteBuffer leaf = pages.currentPayload(pageId);
    status = validCurrentPage(
        pageId, leaf, LogicalHeadPageCodec.TABLE_LEAF, leafOrdinal, 0);
    if (status.isOk()) {
      int slot = (int) (index % LogicalHeadPageCodec.TABLE_LEAF_ENTRIES);
      result.set(
          pageId, slot, LogicalHeadPageCodec.tableRootPageId(leaf, slot),
          LogicalHeadPageCodec.tableRootLevel(leaf, slot));
      status = validRoot(result) ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    pages.unpinCurrentPage(pageId);
    return status;
  }

  StatusCode prepare(long tableId, IndexedHeadTableRoot result) {
    if (!CatalogKeyspace.validObjectHead(tableId) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    long index = tableId - 1;
    long leafOrdinal = index / LogicalHeadPageCodec.TABLE_LEAF_ENTRIES;
    StatusCode status = StatusCode.OK;
    int pageId = IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID;
    long base = 0;
    for (int level = LogicalHeadPageCodec.TABLE_ROOT_LEVEL;
        status.isOk() && level > 0; level--) {
      status = pages.pinLogicalHeadOperationPage(pageId, false, 0, parentPin);
      if (!status.isOk()) break;
      ByteBuffer page = parentPin.payload();
      status = validPage(page, LogicalHeadPageCodec.TABLE_BRANCH, 0, base, level);
      int slot = branchSlot(leafOrdinal, level);
      int child = status.isOk() ? LogicalHeadPageCodec.branchChild(page, slot) : 0;
      long childBase = base + (long) slot * tableSpan(level);
      if (status.isOk() && child == 0) {
        status = release(parentPin, status);
        if (status.isOk()) status = pages.pinLogicalHeadOperationPage(
            pageId, true, 0, parentPin);
        if (status.isOk()) {
          page = parentPin.payload();
          status = validPage(
              page, LogicalHeadPageCodec.TABLE_BRANCH, 0, base, level);
        }
        if (status.isOk()) status = allocate(
            0, level == 1 ? LogicalHeadPageCodec.TABLE_LEAF
                : LogicalHeadPageCodec.TABLE_BRANCH,
            childBase, level - 1);
        if (status.isOk()) {
          child = newPin.pageId();
          LogicalHeadPageCodec.branchChild(page, slot, child);
        }
        status = release(newPin, status);
      }
      status = release(parentPin, status);
      if (status.isOk() && child < 0) status = StatusCode.CORRUPTION;
      pageId = child;
      base = childBase;
    }
    if (status.isOk()) {
      status = pages.pinLogicalHeadOperationPage(pageId, false, 0, parentPin);
      if (status.isOk()) {
        ByteBuffer leaf = parentPin.payload();
        status = validPage(
            leaf, LogicalHeadPageCodec.TABLE_LEAF, 0, leafOrdinal, 0);
        if (status.isOk()) {
          int slot = (int) (index % LogicalHeadPageCodec.TABLE_LEAF_ENTRIES);
          result.set(
              pageId, slot, LogicalHeadPageCodec.tableRootPageId(leaf, slot),
              LogicalHeadPageCodec.tableRootLevel(leaf, slot));
          status = validRoot(result) ? StatusCode.OK : StatusCode.CORRUPTION;
        }
      }
    }
    status = release(parentPin, status);
    return release(metadataPin, status);
  }

  StatusCode update(IndexedHeadTableRoot root, int pageId, int level) {
    if (root == null || root.leafPageId() <= 0 || pageId <= 0 || level < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = pages.pinLogicalHeadOperationPage(
        root.leafPageId(), true, 0, parentPin);
    if (status.isOk()) {
      LogicalHeadPageCodec.tableRoot(parentPin.payload(), root.slot(), pageId, level);
      root.set(root.leafPageId(), root.slot(), pageId, level);
    }
    return release(parentPin, status);
  }

  private StatusCode allocate(long owner, int type, long base, int level) {
    StatusCode status = metadataPin.attached() ? StatusCode.OK
        : pages.pinScalarOperationPage(
            IndexedTableKernel.ROOT_META_PAGE_ID, true, metadataPin);
    if (!status.isOk()) return status;
    status = IndexedOperationPageAllocation.logicalHead(
        pages, metadataPin.payload(), owner, newPin);
    if (status.isOk()) status = LogicalHeadPageCodec.initialize(
        newPin.payload(), type, owner, base, level);
    return status;
  }

  private StatusCode validCurrentPage(
      int pageId, ByteBuffer page, int type, long base, int level) {
    return pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_LOGICAL_HEAD
        && pages.ownerKeyId(pageId) == 0
        ? validPage(page, type, 0, base, level) : StatusCode.CORRUPTION;
  }

  private static StatusCode validPage(
      ByteBuffer page, int type, long owner, long base, int level) {
    return page != null && LogicalHeadPageCodec.validate(page).isOk()
        && LogicalHeadPageCodec.type(page) == type
        && LogicalHeadPageCodec.ownerObjectId(page) == owner
        && LogicalHeadPageCodec.base(page) == base
        && LogicalHeadPageCodec.level(page) == level
            ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static boolean validRoot(IndexedHeadTableRoot root) {
    return root.rootPageId() >= 0
        && (root.rootPageId() != 0 || root.rootLevel() == 0)
        && root.rootLevel() >= 0
        && root.rootLevel() <= LogicalHeadPageCodec.MAXIMUM_ROW_ROOT_LEVEL;
  }

  private void cache(long tableId, int leafPageId) {
    int slot = cacheSlot(tableId);
    cachedTableIds[slot] = tableId;
    cachedLeafPageIds[slot] = leafPageId;
  }

  private static int cacheSlot(long tableId) {
    long mixed = tableId ^ (tableId >>> 16);
    return (int) mixed & (CACHE_SLOTS - 1);
  }

  private static int branchSlot(long leafOrdinal, int level) {
    return (int) ((leafOrdinal / tableSpan(level)) % LogicalHeadPageCodec.BRANCH_ENTRIES);
  }

  private static long tableSpan(int level) {
    long span = 1;
    for (int index = 1; index < level; index++) {
      span *= LogicalHeadPageCodec.BRANCH_ENTRIES;
    }
    return span;
  }

  private StatusCode release(IndexedOperationPage pin, StatusCode status) {
    if (!pin.attached()) return status;
    StatusCode released = pages.releaseOperationPage(pin);
    return status.isOk() ? released : status;
  }
}
