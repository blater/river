package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
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
  private final BTreeLookupResult lookup = new BTreeLookupResult();
  private final IndexedVersionRecord version = new IndexedVersionRecord();
  private final IndexedPageGenerationPin scanPin = new IndexedPageGenerationPin();
  private long resolvedRowId;

  IndexedKernelVisibility(
      IndexedPageSet pageSet,
      IndexedVersionState versionState,
      IndexedKernelRowAccess rowAccess,
      IndexedTableIndexTree indexTree) {
    pages = pageSet;
    versions = versionState;
    rows = rowAccess;
    tree = indexTree;
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
      IndexedVersionedRowResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = lookup(space, key);
    if (status.isOk()) status = resolve(lookup.rowId(), visibleSequence, rowCount);
    if (status.isOk()) status = fetchResolved(rowCount, result.row());
    if (status.isOk()) result.set(resolvedRowId);
    return status;
  }

  StatusCode fetchCurrentSuccessor(
      long space, long key, long candidateRowId, long rowCount,
      IndexedVersionedRowResult result) {
    if (!OrderedKey.isFiniteSpace(space) || candidateRowId <= 0
        || candidateRowId > rowCount || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = lookup(space, key);
    if (!status.isOk()) return status;
    long currentRowId = lookup.rowId();
    long rowId = currentRowId;
    while (rowId > 0) {
      status = versions.lookup(rowId, rowCount, version);
      if (!status.isOk()) return status;
      if (version.deleted()) return StatusCode.CONFLICT;
      if (rowId == candidateRowId) {
        status = rows.fetch(currentRowId, rowCount, result.row());
        if (status.isOk()) result.set(currentRowId);
        return status;
      }
      rowId = version.previousRowId();
    }
    return StatusCode.RETRY;
  }

  StatusCode nextScan(
      IndexedScanCursor cursor, IndexedScanResult result, long rowCount) {
    result.reset();
    while (cursor.leafPageId() > 0) {
      int leafPageId = cursor.leafPageId();
      StatusCode status = pages.pinPageAt(
          leafPageId, cursor.visibleCommitSequence(), scanPin);
      if (!status.isOk()) return status;
      ByteBuffer leaf = scanPin.payload();
      if (scanPin.payloadKind() != io.riverdb.format.page.PageCodec.PAYLOAD_KIND_SCALAR_BTREE
          || scanPin.ownerKeyId() != io.riverdb.format.page.PageCodec.SCALAR_OWNER_KEY_ID
          || BTreePage.type(leaf) != BTreePage.TYPE_LEAF) status = StatusCode.CORRUPTION;
      if (status.isOk()) status = nextEntry(cursor, result, leaf, rowCount);
      if (status == StatusCode.CONFLICT && cursor.leafPageId() != 0) {
        cursor.advanceLeaf(BTreePage.rightSiblingPageId(leaf));
      }
      StatusCode released = pages.unpinPage(scanPin);
      if (status.isOk()) status = released;
      if (status != StatusCode.CONFLICT || cursor.leafPageId() == 0) return status;
    }
    return StatusCode.CONFLICT;
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
    int leafPageId = tree.findLeafPageId(space, key);
    if (leafPageId <= 0) return tree.lookupStatus();
    return BTreePage.lookupLeaf(pages.currentPayload(leafPageId), space, key, lookup);
  }
}
