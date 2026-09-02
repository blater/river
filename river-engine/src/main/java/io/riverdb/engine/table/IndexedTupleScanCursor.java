package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.storage.btree.TupleBTreeCursor;
import io.riverdb.storage.btree.TupleBTreeScanBounds;

/** Caller/session-owned transactional tuple-index cursor. */
public final class IndexedTupleScanCursor {
  private final TupleBTreeCursor cursor = new TupleBTreeCursor();
  private final TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
  private final IndexedTupleScanMerge merge = new IndexedTupleScanMerge();
  private final IndexedTupleScanBinding binding = new IndexedTupleScanBinding();
  private IndexedTransactionSession owner;
  private boolean active;

  public IndexedTupleScanCursor() { }

  StatusCode open(
      IndexedTableKernel kernel, IndexedPageSet pageSet,
      long visible, long current, long ownerObjectId, long keyId, long schemaId,
      long privateOwner,
      TupleShape shape, TupleBTreeScanBounds bounds, IndexedTupleIntentJournal intents) {
    if (active || bounds == null || intents == null) return StatusCode.CONFLICT;
    StatusCode status = binding.open(
        kernel, pageSet, visible, current, ownerObjectId, keyId, schemaId,
        privateOwner, shape, bounds, cursor);
    if (status.isOk()) {
      merge.prepare(intents, keyId, bounds);
      active = true;
    }
    else merge.reset();
    return status;
  }

  StatusCode next(IndexedTupleIntentJournal intents, IndexedTupleScanResult result) {
    return !active || result == null ? StatusCode.CONFLICT
        : merge.next(cursor, entry, intents, result);
  }

  StatusCode close() {
    StatusCode status = cursor.close();
    if (status.isOk()) {
      merge.reset();
      active = false;
      owner = null;
    }
    return status;
  }

  StatusCode attach(IndexedTransactionSession session) {
    if (!active || owner != null) return StatusCode.CONFLICT;
    owner = session;
    return StatusCode.OK;
  }

  boolean ownedBy(IndexedTransactionSession session) { return owner == session; }
  boolean active() { return active; }
}
