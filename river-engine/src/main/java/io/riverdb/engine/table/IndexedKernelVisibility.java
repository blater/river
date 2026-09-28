package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.LogicalHeadPageCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.BTreeLookupResult;
import io.riverdb.storage.btree.BTreePage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Resolves indexed keys and scans against committed version visibility. */
final class IndexedKernelVisibility {
  private final IndexedPageSet pages;
  private final IndexedVersionState versions;
  private final IndexedKernelRowAccess rows;
  private final IndexedTableIndexTree tree;
  private final IndexedLogicalHeadDirectory heads;
  private final IndexedHeadLookupResult headLookup = new IndexedHeadLookupResult();
  private final IndexedHeadLeafResult nextHeadLeaf = new IndexedHeadLeafResult();
  private final IndexedHeadTableRoot nextHeadTable = new IndexedHeadTableRoot();
  private final BTreeLookupResult lookup = new BTreeLookupResult();
  private final IndexedVersionRecord version = new IndexedVersionRecord();
  private final IndexedPageGenerationPin scanPin = new IndexedPageGenerationPin();
  private long resolvedRowId;

  IndexedKernelVisibility(
      IndexedPageSet pageSet,
      IndexedVersionState versionState,
      IndexedKernelRowAccess rowAccess,
      IndexedTableIndexTree indexTree,
      IndexedLogicalHeadDirectory headDirectory) {
    pages = pageSet;
    versions = versionState;
    rows = rowAccess;
    tree = indexTree;
    heads = headDirectory;
  }

  StatusCode fetchByKey(
      long visibleSequence, long space, long key, long rowCount, HeapRowResult result) {
    if (!OrderedKey.isFiniteSpace(space) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lookup(space, key);
    if (!status.isOk()) return status;
    status = resolve(lookup.rowId(), visibleSequence, rowCount);
    return status.isOk() ? fetchResolved(rowCount, result) : status;
  }

  StatusCode fetchVersionedByKey(
      long visibleSequence, long space, long key, long rowCount,
      HeapRowResult row, IndexedVersionedRowResult result) {
    if (!OrderedKey.isFiniteSpace(space) || row == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    row.reset();
    StatusCode status = lookup(space, key);
    if (!status.isOk()) return status;
    status = resolve(lookup.rowId(), visibleSequence, rowCount);
    if (!status.isOk()) return status;
    result.observeCommit(resolvedRowId > 0 ? version.commitSequence() : 0);
    status = fetchResolved(rowCount, row);
    if (status.isOk()) result.set(resolvedRowId);
    return status;
  }

  StatusCode fetchCurrentSuccessor(
      long space, long key, long candidateRowId, long rowCount,
      HeapRowResult row, IndexedVersionedRowResult result) {
    if (!OrderedKey.isFiniteSpace(space) || candidateRowId <= 0
        || candidateRowId > rowCount || row == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    row.reset();
    StatusCode status = lookup(space, key);
    if (!status.isOk()) return status;
    long currentRowId = lookup.rowId();
    long rowId = currentRowId;
    while (rowId > 0) {
      status = versions.lookup(rowId, rowCount, version);
      if (!status.isOk()) return status;
      result.observeCommit(Math.max(result.observedCommitSequence(), version.commitSequence()));
      if (version.deleted()) return StatusCode.CONFLICT;
      if (rowId == candidateRowId) {
        status = rows.fetch(currentRowId, rowCount, row);
        if (status.isOk()) result.set(currentRowId);
        return status;
      }
      rowId = version.previousRowId();
    }
    return StatusCode.RETRY;
  }

  StatusCode nextScan(
      IndexedScanCursor cursor, IndexedScanResult result, long rowCount) {
    if (cursor.mixed()) return nextMixedScan(cursor, result, rowCount);
    if (cursor.headDirectory()) return nextHeadScan(cursor, result, rowCount);
    return nextScalarScan(cursor, result, rowCount);
  }

  private StatusCode nextScalarScan(
      IndexedScanCursor cursor, IndexedScanResult result, long rowCount) {
    result.reset();
    while (cursor.leafPageId() > 0) {
      int leafPageId = cursor.leafPageId();
      StatusCode status = pages.pinPageAt(
          leafPageId, cursor.visibleCommitSequence(), scanPin);
      if (!status.isOk()) return status;
      ByteBuffer leaf = scanPin.payload();
      status = scanLeaf(cursor, result, leaf, rowCount);
      StatusCode released = pages.unpinPage(scanPin);
      if (status.isOk()) status = released;
      if (status != StatusCode.CONFLICT || cursor.leafPageId() == 0) return status;
    }
    return StatusCode.CONFLICT;
  }

  private StatusCode nextMixedScan(
      IndexedScanCursor cursor, IndexedScanResult result, long rowCount) {
    result.reset();
    if (!cursor.mixedScalarReady() && !cursor.mixedScalarDone()) {
      StatusCode status = nextScalarScan(cursor, cursor.mixedScalarResult(), rowCount);
      if (status == StatusCode.CONFLICT) cursor.finishMixedScalar();
      else if (!status.isOk()) return status;
      else cursor.mixedScalarReady(true);
    }
    if (!cursor.mixedHeadReady() && !cursor.mixedHeadDone()) {
      StatusCode status = fillMixedHead(cursor, rowCount);
      if (!status.isOk()) return status;
    }
    if (!cursor.mixedScalarReady() && !cursor.mixedHeadReady()) {
      return StatusCode.CONFLICT;
    }
    if (cursor.mixedScalarReady()
        && (!cursor.mixedHeadReady()
            || OrderedKey.compare(
                cursor.mixedScalarResult().keySpace(),
                cursor.mixedScalarResult().key(),
                cursor.mixedHeadResult().keySpace(),
                cursor.mixedHeadResult().key()) <= 0)) {
      result.copyFrom(cursor.mixedScalarResult());
      cursor.mixedScalarReady(false);
    } else {
      result.copyFrom(cursor.mixedHeadResult());
      cursor.mixedHeadReady(false);
    }
    return StatusCode.OK;
  }

  private StatusCode fillMixedHead(IndexedScanCursor cursor, long rowCount) {
    IndexedScanCursor head = cursor.mixedHeadCursor();
    while (true) {
      if (head.isActive()) {
        StatusCode status = nextHeadScan(head, cursor.mixedHeadResult(), rowCount);
        cursor.observeCommit(head.observedCommitSequence());
        if (status.isOk()) {
          cursor.mixedHeadReady(true);
          return StatusCode.OK;
        }
        if (status != StatusCode.CONFLICT) return status;
        head.complete();
        head.reset();
      }
      if (!CatalogKeyspace.validObjectHead(cursor.nextHeadTableId())) {
        cursor.finishMixedHead();
        return StatusCode.OK;
      }
      StatusCode status = heads.nextTableAtOrAfter(
          cursor.nextHeadTableId(), nextHeadTable);
      if (status == StatusCode.CONFLICT) {
        cursor.finishMixedHead();
        return StatusCode.OK;
      }
      if (!status.isOk()) return status;
      long tableId = nextHeadTable.tableId();
      long space = CatalogKeyspace.relationalBaseRowSpace(tableId);
      cursor.nextHeadTableId(tableId + 1);
      if (!OrderedKey.isInfinity(cursor.upperSpace(), cursor.upperKey())
          && (space > cursor.upperSpace()
              || space == cursor.upperSpace() && cursor.upperKey() <= 1)) {
        cursor.finishMixedHead();
        return StatusCode.OK;
      }
      long lowerKey = space == cursor.lowerSpace() ? cursor.lowerKey() : Long.MIN_VALUE;
      long upperSpace = space == cursor.upperSpace() ? space : space + 1;
      long upperKey = space == cursor.upperSpace()
          ? cursor.upperKey() : Long.MIN_VALUE;
      long minimumOrdinal = lowerKey <= 1 ? 0
          : (lowerKey - 1) / LogicalHeadPageCodec.ROW_LEAF_ENTRIES;
      status = heads.findLeafAtOrAfter(tableId, minimumOrdinal, nextHeadLeaf);
      if (status == StatusCode.CONFLICT) continue;
      if (!status.isOk()) return status;
      status = head.claimHead(
          cursor.owner(), cursor.visibleCommitSequence(), space, lowerKey,
          upperSpace, upperKey, nextHeadLeaf.pageId(), nextHeadLeaf.ordinal());
      if (!status.isOk()) return status;
    }
  }

  private StatusCode nextHeadScan(
      IndexedScanCursor cursor, IndexedScanResult result, long rowCount) {
    result.reset();
    long tableId = cursor.lowerSpace() - CatalogKeyspace.FIRST_RELATIONAL_SPACE;
    while (cursor.leafPageId() > 0) {
      int pageId = cursor.leafPageId();
      StatusCode status = pages.pinCurrentPage(pageId);
      if (!status.isOk()) return status;
      try {
        ByteBuffer leaf = pages.currentPayload(pageId);
        if (leaf == null || pages.payloadKind(pageId) != PageCodec.PAYLOAD_KIND_LOGICAL_HEAD
            || pages.ownerKeyId(pageId) != tableId
            || !LogicalHeadPageCodec.validate(leaf).isOk()
            || LogicalHeadPageCodec.type(leaf) != LogicalHeadPageCodec.ROW_LEAF
            || LogicalHeadPageCodec.ownerObjectId(leaf) != tableId
            || LogicalHeadPageCodec.base(leaf) != cursor.headLeafOrdinal()) {
          return StatusCode.CORRUPTION;
        }
        status = nextHeadEntry(cursor, result, leaf, rowCount);
      } finally {
        pages.unpinCurrentPage(pageId);
      }
      if (status != StatusCode.CONFLICT) return status;
      if (cursor.leafPageId() == 0) return StatusCode.CONFLICT;
      status = heads.findLeafAtOrAfter(
          tableId, cursor.headLeafOrdinal() + 1, nextHeadLeaf);
      if (status == StatusCode.CONFLICT) {
        cursor.advanceHeadLeaf(0, 0);
        return StatusCode.CONFLICT;
      }
      if (!status.isOk()) return status;
      cursor.advanceHeadLeaf(nextHeadLeaf.pageId(), nextHeadLeaf.ordinal());
    }
    return StatusCode.CONFLICT;
  }

  private StatusCode nextHeadEntry(
      IndexedScanCursor cursor, IndexedScanResult result,
      ByteBuffer leaf, long rowCount) {
    long base = cursor.headLeafOrdinal();
    while (cursor.entryIndex() < LogicalHeadPageCodec.ROW_LEAF_ENTRIES) {
      int entry = cursor.entryIndex();
      cursor.advanceEntry();
      if (base > (Long.MAX_VALUE - entry - 1)
          / LogicalHeadPageCodec.ROW_LEAF_ENTRIES) return StatusCode.CORRUPTION;
      long key = base * LogicalHeadPageCodec.ROW_LEAF_ENTRIES + entry + 1;
      long space = cursor.lowerSpace();
      if (OrderedKey.compare(space, key, cursor.lowerSpace(), cursor.lowerKey()) < 0) {
        continue;
      }
      if (!OrderedKey.lessThan(space, key, cursor.upperSpace(), cursor.upperKey())) {
        cursor.advanceHeadLeaf(0, 0);
        return StatusCode.CONFLICT;
      }
      long head = LogicalHeadPageCodec.rowHead(leaf, entry);
      if (head == 0) continue;
      if (head < 0) return StatusCode.CORRUPTION;
      StatusCode status = resolve(head, cursor.visibleCommitSequence(), rowCount);
      if (!status.isOk()) return status;
      if (resolvedRowId <= 0) continue;
      status = versions.lookup(resolvedRowId, rowCount, version);
      if (!status.isOk()) return status;
      cursor.observeCommit(version.commitSequence());
      if (version.deleted()) continue;
      status = rows.fetch(resolvedRowId, rowCount, result.row());
      if (!status.isOk()) return status;
      result.setCommitted(space, key, resolvedRowId);
      return StatusCode.OK;
    }
    return StatusCode.CONFLICT;
  }

  private StatusCode scanLeaf(
      IndexedScanCursor cursor, IndexedScanResult result,
      ByteBuffer leaf, long rowCount) {
    if (scanPin.payloadKind() != io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE
        || scanPin.ownerKeyId() != io.riverdb.format.page.PageCodec.SCALAR_OWNER_KEY_ID
        || BTreePage.type(leaf) != BTreePage.TYPE_LEAF) return StatusCode.CORRUPTION;
    StatusCode status = nextEntry(cursor, result, leaf, rowCount);
    if (status == StatusCode.CONFLICT && cursor.leafPageId() != 0) {
      cursor.advanceLeaf(BTreePage.rightSiblingPageId(leaf));
    }
    return status;
  }

  StatusCode prepareMutation(
      long visibleSequence, long space, long key, long rowCount,
      boolean insert, IndexedMutationTarget result) {
    if (visibleSequence < 0 || !OrderedKey.isFiniteSpace(space) || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = lookup(space, key);
    if (insert && status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    long latestRowId = lookup.rowId();
    status = versions.lookup(latestRowId, rowCount, version);
    if (!status.isOk()) return status;
    result.observeCommit(version.commitSequence());
    if (version.commitSequence() > visibleSequence
        || insert != version.deleted()) {
      return StatusCode.CONFLICT;
    }
    result.set(latestRowId);
    return StatusCode.OK;
  }

  private StatusCode nextEntry(
      IndexedScanCursor cursor, IndexedScanResult result,
      ByteBuffer leaf, long rowCount) {
    int entries = BTreePage.entryCount(leaf);
    while (cursor.entryIndex() < entries) {
      int entry = cursor.entryIndex();
      cursor.advanceEntry();
      long key = BTreePage.keyAt(leaf, entry);
      long space = BTreePage.spaceAt(leaf, entry);
      if (OrderedKey.compare(space, key, cursor.lowerSpace(), cursor.lowerKey()) < 0) continue;
      if (!OrderedKey.lessThan(space, key, cursor.upperSpace(), cursor.upperKey())) {
        cursor.advanceLeaf(0);
        return StatusCode.CONFLICT;
      }
      StatusCode status = resolve(
          BTreePage.leafValueAt(leaf, entry), cursor.visibleCommitSequence(), rowCount);
      if (!status.isOk()) return status;
      if (resolvedRowId <= 0) continue;
      status = versions.lookup(resolvedRowId, rowCount, version);
      if (!status.isOk()) return status;
      cursor.observeCommit(version.commitSequence());
      if (version.deleted()) continue;
      status = rows.fetch(resolvedRowId, rowCount, result.row());
      if (!status.isOk()) return status;
      result.setCommitted(space, key, resolvedRowId);
      return StatusCode.OK;
    }
    return StatusCode.CONFLICT;
  }

  private StatusCode resolve(long rowId, long visibleSequence, long rowCount) {
    resolvedRowId = rowId;
    while (resolvedRowId > 0) {
      StatusCode status = versions.lookup(resolvedRowId, rowCount, version);
      if (!status.isOk()) return status;
      if (version.commitSequence() <= visibleSequence) return StatusCode.OK;
      resolvedRowId = version.previousRowId();
    }
    return StatusCode.OK;
  }

  private StatusCode fetchResolved(long rowCount, HeapRowResult result) {
    if (resolvedRowId <= 0) {
      result.reset();
      return StatusCode.CONFLICT;
    }
    StatusCode status = versions.lookup(resolvedRowId, rowCount, version);
    if (!status.isOk()) return status;
    if (version.deleted()) {
      result.reset();
      return StatusCode.CONFLICT;
    }
    return rows.fetch(resolvedRowId, rowCount, result);
  }

  private StatusCode lookup(long space, long key) {
    if (CatalogKeyspace.isRelationalBaseRowSpace(space)) {
      StatusCode status = heads.lookup(
          space - CatalogKeyspace.FIRST_RELATIONAL_SPACE, key, headLookup);
      if (status.isOk()) lookup.setRowId(headLookup.rowId());
      return status;
    }
    int leafPageId = tree.findLeafPageId(space, key);
    if (leafPageId <= 0) return tree.lookupStatus();
    return BTreePage.lookupLeaf(pages.currentPayload(leafPageId), space, key, lookup);
  }
}
