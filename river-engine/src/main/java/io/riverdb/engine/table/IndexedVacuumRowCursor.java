package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.LogicalHeadPageCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable cursor owning one scalar-leaf pin and, while published, one heap-row pin. */
final class IndexedVacuumRowCursor {
  private final IndexedTableKernel table;
  private final IndexedPageSet pages;
  private final IndexedRowPin rowPin = new IndexedRowPin();
  private ByteBuffer leaf;
  private ByteBuffer headLeaf;
  private long rowsToSkip;
  private int pageId;
  private int pinnedPageId;
  private int headPinnedPageId;
  private int nextHeadPageId;
  private int headEntry;
  private int entry;
  private long currentRowId;
  private long currentSpace;
  private long currentKey;
  private long expectedSpace;
  private long expectedKey;
  private boolean hasExpectedKey;
  private boolean exhausted;
  private boolean scalarExhausted;

  IndexedVacuumRowCursor(IndexedTableKernel tableKernel, IndexedPageSet pageSet) {
    table = tableKernel;
    pages = pageSet;
  }

  StatusCode reset(long firstRow) {
    StatusCode status = close();
    if (!status.isOk() || firstRow < 0) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    rowsToSkip = firstRow;
    pageId = 0;
    entry = 0;
    currentRowId = 0;
    currentSpace = 0;
    currentKey = 0;
    expectedSpace = 0;
    expectedKey = 0;
    hasExpectedKey = false;
    exhausted = false;
    scalarExhausted = false;
    nextHeadPageId = IndexedTableKernel.HEAD_TABLE_ROOT_PAGE_ID;
    headEntry = 0;
    return openFirstLeaf();
  }

  StatusCode next(HeapRowResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = releaseRow();
    if (!status.isOk()) return status;
    boolean finished = false;
    while (!finished) {
      if (scalarExhausted) {
        status = nextHead(result);
        finished = status != StatusCode.RETRY;
      } else if (leaf != null && entry < BTreePage.entryCount(leaf)) {
        int current = entry++;
        if (rowsToSkip > 0) {
          rowsToSkip--;
        } else {
          currentRowId = BTreePage.leafValueAt(leaf, current);
          currentSpace = BTreePage.spaceAt(leaf, current);
          currentKey = BTreePage.keyAt(leaf, current);
          status = table.pinRow(currentRowId, result, rowPin);
          finished = true;
        }
      } else {
        status = nextLeaf();
        finished = !status.isOk();
      }
    }
    return status;
  }

  StatusCode close() {
    StatusCode status = releaseRow();
    if (pinnedPageId != 0) pages.unpinCurrentPage(pinnedPageId);
    if (headPinnedPageId != 0) pages.unpinCurrentPage(headPinnedPageId);
    pinnedPageId = 0;
    headPinnedPageId = 0;
    leaf = null;
    headLeaf = null;
    return status;
  }

  boolean exhausted() {
    return exhausted;
  }

  long rowId() { return currentRowId; }
  long space() { return currentSpace; }
  long key() { return currentKey; }

  private StatusCode nextLeaf() {
    if (leaf == null) return StatusCode.CORRUPTION;
    int candidate = BTreePage.rightSiblingPageId(leaf);
    expectedSpace = BTreePage.highSpace(leaf);
    expectedKey = BTreePage.highKey(leaf);
    hasExpectedKey = true;
    close();
    if (candidate == 0) {
      scalarExhausted = OrderedKey.isInfinity(expectedSpace, expectedKey);
      return scalarExhausted ? StatusCode.OK : StatusCode.CORRUPTION;
    }
    if (!validPageId(candidate)) return StatusCode.CORRUPTION;
    StatusCode status = pages.pinCurrentPage(candidate);
    if (!status.isOk()) return status;
    ByteBuffer payload = pages.currentPayloadUnchecked(candidate);
    status = validateLeaf(payload);
    if (!status.isOk()) {
      pages.unpinCurrentPage(candidate);
      return status;
    }
    pinnedPageId = candidate;
    pageId = candidate;
    leaf = payload;
    entry = 0;
    return StatusCode.OK;
  }

  private StatusCode nextHead(HeapRowResult result) {
    while (true) {
      if (headLeaf != null) {
        while (headEntry < LogicalHeadPageCodec.ROW_LEAF_ENTRIES) {
          int slot = headEntry++;
          long head = LogicalHeadPageCodec.rowHead(headLeaf, slot);
          if (head == 0) continue;
          long base = LogicalHeadPageCodec.base(headLeaf);
          if (head < 0 || base > (Long.MAX_VALUE - slot - 1)
              / LogicalHeadPageCodec.ROW_LEAF_ENTRIES) return StatusCode.CORRUPTION;
          if (rowsToSkip > 0) {
            rowsToSkip--;
            continue;
          }
          currentRowId = head;
          currentSpace = CatalogKeyspace.relationalBaseRowSpace(
              LogicalHeadPageCodec.ownerObjectId(headLeaf));
          currentKey = base * LogicalHeadPageCodec.ROW_LEAF_ENTRIES + slot + 1;
          return table.pinRow(currentRowId, result, rowPin);
        }
        pages.unpinCurrentPage(headPinnedPageId);
        headPinnedPageId = 0;
        headLeaf = null;
      }
      while (nextHeadPageId <= pages.highestPageId()
          && pages.payloadKind(nextHeadPageId) != PageCodec.PAYLOAD_KIND_LOGICAL_HEAD) {
        nextHeadPageId++;
      }
      if (nextHeadPageId > pages.highestPageId()) {
        exhausted = true;
        return StatusCode.CONFLICT;
      }
      int candidate = nextHeadPageId++;
      StatusCode status = pages.pinCurrentPage(candidate);
      if (!status.isOk()) return status;
      ByteBuffer payload = pages.currentPayload(candidate);
      if (payload == null || !LogicalHeadPageCodec.validate(payload).isOk()
          || pages.ownerKeyId(candidate) != LogicalHeadPageCodec.ownerObjectId(payload)) {
        pages.unpinCurrentPage(candidate);
        return StatusCode.CORRUPTION;
      }
      if (LogicalHeadPageCodec.type(payload) != LogicalHeadPageCodec.ROW_LEAF) {
        pages.unpinCurrentPage(candidate);
        continue;
      }
      headPinnedPageId = candidate;
      headLeaf = payload;
      headEntry = 0;
    }
  }

  private StatusCode openFirstLeaf() {
    StatusCode status = pages.pinCurrentPage(IndexedTableKernel.ROOT_META_PAGE_ID);
    if (!status.isOk()) return status;
    try {
      ByteBuffer metadata = pages.currentPayload(IndexedTableKernel.ROOT_META_PAGE_ID);
      if (metadata == null) return pages.lastStatus();
      status = BTreeRootPage.validate(metadata);
      if (!status.isOk()) return status;
      pageId = BTreeRootPage.rootPageId(metadata);
    } finally {
      pages.unpinCurrentPage(IndexedTableKernel.ROOT_META_PAGE_ID);
    }
    for (int depth = 0; BTreeStructuralLimits.canVisitLevel(depth); depth++) {
      if (!validPageId(pageId)) return StatusCode.CORRUPTION;
      status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      ByteBuffer payload = pages.currentPayload(pageId);
      if (payload == null) {
        pages.unpinCurrentPage(pageId);
        return pages.lastStatus();
      }
      status = HeapPage.isHeap(payload)
          ? StatusCode.CORRUPTION : BTreePage.validate(payload);
      if (status.isOk()
          && BTreePage.type(payload) == BTreePage.TYPE_LEAF
          && (depth == 0 || BTreePage.entryCount(payload) > 0)) {
        pinnedPageId = pageId;
        leaf = payload;
        return StatusCode.OK;
      }
      int child = status.isOk() && BTreePage.type(payload) == BTreePage.TYPE_INTERNAL
          ? BTreePage.firstChildPageId(payload) : 0;
      pages.unpinCurrentPage(pageId);
      if (!status.isOk()
          || !BTreeStructuralLimits.canDescendFrom(depth)
          || !BTreeStructuralLimits.validPageId(child)) {
        return StatusCode.CORRUPTION;
      }
      pageId = child;
    }
    return StatusCode.CORRUPTION;
  }

  private boolean validPageId(int candidate) {
    return candidate != IndexedTableKernel.ROOT_META_PAGE_ID
        && BTreeStructuralLimits.validPageId(candidate)
        && candidate <= pages.highestPageId()
        && pages.isPresent(candidate)
        && pages.payloadKind(candidate) == PageCodec.PAYLOAD_KIND_SCALAR_BTREE;
  }

  private StatusCode validateLeaf(ByteBuffer payload) {
    StatusCode status = HeapPage.isHeap(payload)
        ? StatusCode.CORRUPTION : BTreePage.validate(payload);
    if (!status.isOk() || BTreePage.type(payload) != BTreePage.TYPE_LEAF) {
      return StatusCode.CORRUPTION;
    }
    int entries = BTreePage.entryCount(payload);
    if (hasExpectedKey
        && (entries == 0
            || !OrderedKey.equal(
                BTreePage.spaceAt(payload, 0), BTreePage.keyAt(payload, 0),
                expectedSpace, expectedKey))) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private StatusCode releaseRow() {
    return rowPin.attached() ? table.releaseRow(rowPin) : StatusCode.OK;
  }
}
