package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.tx.api.lock.LockMode;
import java.nio.ByteBuffer;

/** Current-committed tuple probes used only after integrity protection is retained. */
final class IndexedTupleCurrentResolution {
  private final IndexedTransactionSession session;
  private final IndexedTuplePublishedRootProbe published;
  private final IndexedTupleProbeResult probe = new IndexedTupleProbeResult();

  IndexedTupleCurrentResolution(
      IndexedTransactionSession owner, IndexedTuplePublishedRootProbe publishedRoot) {
    session = owner;
    published = publishedRoot;
  }

  StatusCode unique(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length,
      IndexedTupleProbeResult result) {
    if (result == null || !session.activeTransaction()
        || !IndexedTupleLockKey.valid(key, offset, length)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = IndexedTupleKeyProtection.protectShared(
        session, keyId, key, offset, length);
    return status.isOk() ? protectedUnique(
        ownerId, keyId, schemaId, shape, key, offset, length, result) : status;
  }

  StatusCode protectedUnique(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length,
      IndexedTupleProbeResult result) {
    if (result == null || !session.activeTransaction()
        || !IndexedTupleLockKey.valid(key, offset, length)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    probe.reset();
    StatusCode status = published.current(
        ownerId, keyId, schemaId, shape, key, offset, length, probe);
    return status.isOk() ? session.tupleIntents().resolveUniquePrefix(
        keyId, shape, key, offset, length,
        probe.found(), probe.logicalRowId(), result) : status;
  }

  StatusCode any(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, long excludedRowId,
      IndexedTupleProbeResult result) {
    if (result == null || !session.activeTransaction()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = session.protectKey(
        CatalogKeyspace.INDEX_ROOT_SPACE, keyId, LockMode.SHARED);
    if (!status.isOk()) return status;
    long pending = session.tupleIntents().anyInsertPrefixRowId(
        keyId, shape, key, offset, length, excludedRowId);
    if (pending > 0) {
      result.set(pending);
      return StatusCode.OK;
    }
    long after = 0;
    do {
      status = published.currentAfter(
          ownerId, keyId, schemaId, shape, key, offset, length, after, probe);
      if (!status.isOk() || !probe.found()) return status;
      after = probe.logicalRowId();
    } while (after == excludedRowId || session.tupleIntents().deletesPrefixRow(
        keyId, shape, key, offset, length, after));
    result.set(after);
    return StatusCode.OK;
  }
}
