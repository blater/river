package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.LogicalHeadPageCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.heap.HeapInsertResult;
import java.nio.ByteBuffer;

/** Direct table-local logical head access over sparse, WAL-published pages. */
final class IndexedLogicalHeadDirectory {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedHeadTableRootMap tables;
  private final IndexedHeadTableRoot tableRoot = new IndexedHeadTableRoot();
  private final IndexedOperationPage metadataPin = new IndexedOperationPage();
  private final IndexedOperationPage parentPin = new IndexedOperationPage();
  private final IndexedOperationPage leafPin = new IndexedOperationPage();
  private final IndexedOperationPage newPin = new IndexedOperationPage();

  IndexedLogicalHeadDirectory(IndexedTableKernel table, IndexedPageSet pageSet) {
    kernel = table;
    pages = pageSet;
    tables = new IndexedHeadTableRootMap(pageSet);
  }

  StatusCode nextTableAtOrAfter(long minimumTableId, IndexedHeadTableRoot result) {
    return tables.nextTableAtOrAfter(minimumTableId, result);
  }

  StatusCode findLeafAtOrAfter(
      long tableId, long minimumOrdinal, IndexedHeadLeafResult result) {
    if (!CatalogKeyspace.validObjectHead(tableId)
        || minimumOrdinal < 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = tables.load(tableId, tableRoot);
    if (!status.isOk() || tableRoot.rootPageId() == 0) {
      return status.isOk() ? StatusCode.CONFLICT : status;
    }
    return findLeaf(
        tableRoot.rootPageId(), tableId, 0, tableRoot.rootLevel(),
        minimumOrdinal, result);
  }

  private StatusCode findLeaf(
      int pageId, long tableId, long base, int level,
      long minimumOrdinal, IndexedHeadLeafResult result) {
    StatusCode status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    ByteBuffer page = pages.currentPayload(pageId);
    status = validCurrentPage(
        pageId, page, level == 0 ? LogicalHeadPageCodec.ROW_LEAF
            : LogicalHeadPageCodec.ROW_BRANCH, tableId, base, level);
    if (level == 0) {
      pages.unpinCurrentPage(pageId);
      if (!status.isOk()) return status;
      if (base < minimumOrdinal) return StatusCode.CONFLICT;
      result.set(pageId, base);
      return StatusCode.OK;
    }
    if (!status.isOk()) {
      pages.unpinCurrentPage(pageId);
      return status;
    }
    long span = rowSpan(level);
    int first = minimumOrdinal <= base
        ? 0 : (int) Math.min(LogicalHeadPageCodec.BRANCH_ENTRIES,
            (minimumOrdinal - base) / span);
    for (int slot = first; slot < LogicalHeadPageCodec.BRANCH_ENTRIES; slot++) {
      int child = LogicalHeadPageCodec.branchChild(page, slot);
      if (child < 0) {
        pages.unpinCurrentPage(pageId);
        return StatusCode.CORRUPTION;
      }
      if (child == 0) continue;
      long childBase = base + (long) slot * span;
      pages.unpinCurrentPage(pageId);
      status = findLeaf(
          child, tableId, childBase, level - 1, minimumOrdinal, result);
      if (status != StatusCode.CONFLICT) return status;
      status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      page = pages.currentPayload(pageId);
      status = validCurrentPage(
          pageId, page, LogicalHeadPageCodec.ROW_BRANCH,
          tableId, base, level);
      if (!status.isOk()) {
        pages.unpinCurrentPage(pageId);
        return status;
      }
    }
    pages.unpinCurrentPage(pageId);
    return StatusCode.CONFLICT;
  }

  StatusCode lookup(long tableId, long logicalRowId, IndexedHeadLookupResult result) {
    if (!validAddress(tableId, logicalRowId) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = tables.load(tableId, tableRoot);
    if (!status.isOk() || tableRoot.rootPageId() == 0) {
      return status.isOk() ? StatusCode.CONFLICT : status;
    }
    long leafOrdinal = (logicalRowId - 1) / LogicalHeadPageCodec.ROW_LEAF_ENTRIES;
    if (!covered(leafOrdinal, tableRoot.rootLevel())) return StatusCode.CONFLICT;
    int pageId = tableRoot.rootPageId();
    long base = 0;
    for (int level = tableRoot.rootLevel(); level > 0; level--) {
      status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      ByteBuffer page = pages.currentPayload(pageId);
      status = validCurrentPage(
          pageId, page, LogicalHeadPageCodec.ROW_BRANCH,
          tableId, base, level);
      int slot = branchSlot(leafOrdinal, level);
      int child = status.isOk() ? LogicalHeadPageCodec.branchChild(page, slot) : 0;
      pages.unpinCurrentPage(pageId);
      if (!status.isOk()) return status;
      if (child < 0) return StatusCode.CORRUPTION;
      if (child == 0) return StatusCode.CONFLICT;
      base += (long) slot * rowSpan(level);
      pageId = child;
    }
    status = pages.pinCurrentPage(pageId);
    if (!status.isOk()) return status;
    ByteBuffer leaf = pages.currentPayload(pageId);
    status = validCurrentPage(
        pageId, leaf, LogicalHeadPageCodec.ROW_LEAF,
        tableId, leafOrdinal, 0);
    int slot = (int) ((logicalRowId - 1) % LogicalHeadPageCodec.ROW_LEAF_ENTRIES);
    long head = status.isOk() ? LogicalHeadPageCodec.rowHead(leaf, slot) : 0;
    pages.unpinCurrentPage(pageId);
    if (!status.isOk()) return status;
    if (head <= 0) return head == 0 ? StatusCode.CONFLICT : StatusCode.CORRUPTION;
    result.set(head, pageId, slot);
    return StatusCode.OK;
  }

  StatusCode stage(
      long tableId, long logicalRowId, long previousRowId,
      ByteBuffer row, boolean deleted, HeapInsertResult inserted) {
    if (!validAddress(tableId, logicalRowId) || previousRowId < 0
        || row == null || inserted == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = tables.prepare(tableId, tableRoot);
    if (!status.isOk()) return status;
    long leafOrdinal = (logicalRowId - 1) / LogicalHeadPageCodec.ROW_LEAF_ENTRIES;
    status = ensureRoot(tableId, leafOrdinal);
    if (status.isOk()) status = stageRow(
        tableId, logicalRowId, leafOrdinal, previousRowId, row, deleted, inserted);
    status = release(newPin, status);
    status = release(leafPin, status);
    status = release(parentPin, status);
    return release(metadataPin, status);
  }

  private StatusCode ensureRoot(long tableId, long leafOrdinal) {
    int requiredLevel = requiredLevel(leafOrdinal);
    int pageId = tableRoot.rootPageId();
    int level = tableRoot.rootLevel();
    if (pageId == 0) {
      StatusCode status = allocate(
          tableId, requiredLevel == 0 ? LogicalHeadPageCodec.ROW_LEAF
              : LogicalHeadPageCodec.ROW_BRANCH,
          0, requiredLevel);
      if (!status.isOk()) return status;
      pageId = newPin.pageId();
      level = requiredLevel;
      status = release(newPin, status);
      return status.isOk() ? tables.update(tableRoot, pageId, level) : status;
    }
    while (level < requiredLevel) {
      StatusCode status = allocate(
          tableId, LogicalHeadPageCodec.ROW_BRANCH, 0, level + 1);
      if (!status.isOk()) return status;
      int nextRoot = newPin.pageId();
      LogicalHeadPageCodec.branchChild(newPin.payload(), 0, pageId);
      status = release(newPin, status);
      if (!status.isOk()) return status;
      pageId = nextRoot;
      level++;
    }
    return level == tableRoot.rootLevel()
        ? StatusCode.OK : tables.update(tableRoot, pageId, level);
  }

  private StatusCode stageRow(
      long tableId, long logicalRowId, long leafOrdinal,
      long previousRowId, ByteBuffer row, boolean deleted,
      HeapInsertResult inserted) {
    int pageId = tableRoot.rootPageId();
    long base = 0;
    StatusCode status = StatusCode.OK;
    for (int level = tableRoot.rootLevel(); status.isOk() && level > 0; level--) {
      status = pages.pinLogicalHeadOperationPage(pageId, false, tableId, parentPin);
      if (!status.isOk()) break;
      ByteBuffer page = parentPin.payload();
      status = validPage(
          page, LogicalHeadPageCodec.ROW_BRANCH, tableId, base, level);
      int slot = branchSlot(leafOrdinal, level);
      int child = status.isOk() ? LogicalHeadPageCodec.branchChild(page, slot) : 0;
      long childBase = base + (long) slot * rowSpan(level);
      if (status.isOk() && child == 0) {
        status = release(parentPin, status);
        if (status.isOk()) status = pages.pinLogicalHeadOperationPage(
            pageId, true, tableId, parentPin);
        if (status.isOk()) {
          page = parentPin.payload();
          status = validPage(
              page, LogicalHeadPageCodec.ROW_BRANCH, tableId, base, level);
        }
        if (status.isOk()) status = allocate(
            tableId, level == 1 ? LogicalHeadPageCodec.ROW_LEAF
                : LogicalHeadPageCodec.ROW_BRANCH,
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
    if (!status.isOk()) return status;
    status = pages.pinLogicalHeadOperationPage(pageId, true, tableId, leafPin);
    if (!status.isOk()) return status;
    ByteBuffer leaf = leafPin.payload();
    status = validPage(
        leaf, LogicalHeadPageCodec.ROW_LEAF, tableId, leafOrdinal, 0);
    int slot = (int) ((logicalRowId - 1) % LogicalHeadPageCodec.ROW_LEAF_ENTRIES);
    if (status.isOk() && LogicalHeadPageCodec.rowHead(leaf, slot) != previousRowId) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) status = kernel.stageRelationalVersionRow(
        row, row.position(), row.remaining(), previousRowId, deleted, inserted);
    if (status.isOk()) LogicalHeadPageCodec.rowHead(leaf, slot, inserted.rowId());
    return status;
  }

  private StatusCode allocate(long owner, int type, long base, int level) {
    StatusCode status = metadataPin.attached() ? StatusCode.OK
        : pages.pinScalarOperationPage(
            IndexedTableKernel.ROOT_META_PAGE_ID, true, metadataPin);
    if (!status.isOk()) return status;
    status = IndexedOperationPageAllocation.logicalHead(
        pages, metadataPin.payload(), owner, newPin);
    return status.isOk() ? LogicalHeadPageCodec.initialize(
        newPin.payload(), type, owner, base, level) : status;
  }

  private StatusCode validCurrentPage(
      int pageId, ByteBuffer page, int type, long owner, long base, int level) {
    return pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_LOGICAL_HEAD
        && pages.ownerKeyId(pageId) == owner
        ? validPage(page, type, owner, base, level) : StatusCode.CORRUPTION;
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

  private static boolean validAddress(long tableId, long logicalRowId) {
    return CatalogKeyspace.validObjectHead(tableId) && logicalRowId > 0;
  }

  private static int requiredLevel(long leafOrdinal) {
    long capacity = 1;
    int level = 0;
    while (leafOrdinal >= capacity) {
      capacity = capacity > Long.MAX_VALUE / LogicalHeadPageCodec.BRANCH_ENTRIES
          ? Long.MAX_VALUE : capacity * LogicalHeadPageCodec.BRANCH_ENTRIES;
      level++;
    }
    return level;
  }

  private static boolean covered(long leafOrdinal, int level) {
    long capacity = 1;
    for (int index = 0; index < level; index++) {
      capacity = capacity > Long.MAX_VALUE / LogicalHeadPageCodec.BRANCH_ENTRIES
          ? Long.MAX_VALUE : capacity * LogicalHeadPageCodec.BRANCH_ENTRIES;
    }
    return leafOrdinal < capacity;
  }

  private static int branchSlot(long leafOrdinal, int level) {
    return (int) ((leafOrdinal / rowSpan(level)) % LogicalHeadPageCodec.BRANCH_ENTRIES);
  }

  private static long rowSpan(int level) {
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
