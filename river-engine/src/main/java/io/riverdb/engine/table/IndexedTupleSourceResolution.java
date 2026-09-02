package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockToken;
import java.nio.ByteBuffer;

/** Stable source-candidate resolution across snapshot discovery and reactive exact locking. */
final class IndexedTupleSourceResolution {
  private final IndexedTransactionSession session;
  private final IndexedSessionTupleAccess tuples;
  private final IndexedTupleCurrentResolution current;
  private final IndexedTupleProbeResult currentResult = new IndexedTupleProbeResult();
  private final LockToken borrowed = new LockToken();

  IndexedTupleSourceResolution(
      IndexedTransactionSession owner,
      IndexedSessionTupleAccess tupleAccess,
      IndexedTupleCurrentResolution currentAccess) {
    session = owner;
    tuples = tupleAccess;
    current = currentAccess;
  }

  StatusCode resolve(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, LockMode mode,
      IndexedTupleProbeResult result) {
    if (!valid(result, key, offset, length, mode)) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE) {
      StatusCode status = protect(keyId, key, offset, length, mode);
      if (status.isOk()) status = current.protectedUnique(
          ownerId, keyId, schemaId, shape, key, offset, length, result);
      return finishSource(status, result.found(), mode);
    }
    StatusCode status = tuples.resolveUnique(
        ownerId, keyId, schemaId, shape, key, offset, length, result);
    if (!status.isOk() || !result.found()) return status;
    long candidate = result.logicalRowId();
    status = protect(keyId, key, offset, length, mode);
    if (status.isOk()) status = current.protectedUnique(
        ownerId, keyId, schemaId, shape, key, offset, length, currentResult);
    if (status.isOk() && (!currentResult.found()
        || currentResult.logicalRowId() != candidate)) result.reset();
    return finishSource(status, result.found(), mode);
  }

  private StatusCode protect(
      long keyId, ByteBuffer key, int offset, int length, LockMode mode) {
    if (mode == LockMode.EXCLUSIVE) {
      return IndexedTupleKeyProtection.protectExclusive(session, keyId, key, offset, length);
    }
    if (borrowed.isActive()) return StatusCode.CONFLICT;
    StatusCode status = session.protectKey(
        io.riverdb.format.catalog.CatalogKeyspace.INDEX_ROOT_SPACE, keyId);
    return status.isOk() ? session.lockWait().acquireBorrowedTupleKey(
        session.transaction(), keyId, key,
        IndexedTupleLockKey.userOffset(key, offset, length),
        IndexedTupleLockKey.userLength(key, offset, length), mode, borrowed) : status;
  }

  boolean borrowed() { return borrowed.isActive(); }

  StatusCode retain() {
    if (!borrowed.isActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = session.lockWait().retain(session.transaction(), borrowed);
    return finishBorrowed(status);
  }

  StatusCode release() {
    if (!borrowed.isActive()) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = session.lockWait().release(session.transaction(), borrowed);
    return finishBorrowed(status);
  }

  private StatusCode finishSource(StatusCode status, boolean selected, LockMode mode) {
    if (mode != LockMode.SHARED || selected && status.isOk() || !borrowed.isActive()) {
      return status;
    }
    StatusCode released = release();
    return status.isOk() ? released : released.isOk() ? status : released;
  }

  private StatusCode finishBorrowed(StatusCode status) {
    if (status.isOk()) {
      StatusCode reset = borrowed.reset();
      return reset.isOk() ? status : reset;
    }
    return status;
  }

  private boolean valid(
      IndexedTupleProbeResult result, ByteBuffer key, int offset, int length, LockMode mode) {
    return result != null && session.activeTransaction()
        && IndexedTupleLockKey.valid(key, offset, length)
        && (mode == LockMode.SHARED || mode == LockMode.EXCLUSIVE);
  }
}
