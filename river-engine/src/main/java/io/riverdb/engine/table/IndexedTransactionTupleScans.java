package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import io.riverdb.tx.api.IsolationLevel;
import java.nio.ByteBuffer;

/** Owns bounded transactional admission for persistent tuple-index cursors. */
final class IndexedTransactionTupleScans {
  private final IndexedTransactionSession session;

  IndexedTransactionTupleScans(IndexedTransactionSession owner) { session = owner; }

  StatusCode begin(
      long ownerObjectId, long keyId, long schemaId,
      io.riverdb.base.tuple.TupleShape shape, TupleBTreeScanBounds bounds,
      IndexedTupleScanCursor cursor) {
    if (cursor == null || bounds == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
    StatusCode status = session.reserveTupleScan();
    if (status.isOk()) status = session.protectKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId);
    if (status.isOk()
        && session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE) {
      status = protectRange(keyId, bounds);
    }
    if (status.isOk()) status = session.selectScanSnapshot();
    long privateOwner = session.tupleLifecycle().publishingPrivateOwner(
        ownerObjectId, keyId, schemaId, shape);
    if (status.isOk()) status = session.table().beginTupleScanAt(
        session.visibleCommitSequence(), ownerObjectId, keyId, schemaId,
        privateOwner, shape, bounds, session.tupleIntents(), cursor);
    if (status.isOk()) status = cursor.attach(session);
    if (status.isOk()) {
      session.registerTupleScan(cursor);
      if (session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE) {
        session.markSerializableScan();
      }
    }
    else if (cursor.active()) session.table().closeTupleScan(cursor);
    return status;
  }

  private StatusCode protectRange(long keyId, TupleBTreeScanBounds bounds) {
    ByteBuffer lower = bounds.lowerKey();
    ByteBuffer upper = bounds.upperKey();
    int lowerOffset = tupleOffset(lower, bounds.lowerOffset(), bounds.lowerLength());
    int upperOffset = tupleOffset(upper, bounds.upperOffset(), bounds.upperLength());
    int lowerLength = tupleLength(lower, bounds.lowerOffset(), bounds.lowerLength());
    int upperLength = tupleLength(upper, bounds.upperOffset(), bounds.upperLength());
    if (lower != null && (lowerOffset < 0 || lowerLength <= 0)
        || upper != null && (upperOffset < 0 || upperLength <= 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return session.acquireSharedTupleRangeForScan(
        keyId,
        lower, lowerOffset, lowerLength, bounds.lowerInclusive(),
        upper, upperOffset, upperLength, bounds.upperInclusive());
  }

  private static int tupleOffset(ByteBuffer key, int offset, int length) {
    return key == null ? 0 : IndexedTupleLockKey.userOffset(key, offset, length);
  }

  private static int tupleLength(ByteBuffer key, int offset, int length) {
    return key == null ? 0 : IndexedTupleLockKey.userLength(key, offset, length);
  }

  StatusCode next(IndexedTupleScanCursor cursor, IndexedTupleScanResult result) {
    if (!session.activeTransaction() || result == null
        || session.findTupleScan(cursor) < 0 || !cursor.ownedBy(session)) {
      return StatusCode.CONFLICT;
    }
    return session.table().nextTupleScan(cursor, session.tupleIntents(), result);
  }

  StatusCode close(IndexedTupleScanCursor cursor) {
    int index = session.findTupleScan(cursor);
    if (index < 0 || !cursor.ownedBy(session)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = session.table().closeTupleScan(cursor);
    if (status.isOk()) session.removeTupleScan(index);
    return status;
  }
}
