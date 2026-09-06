package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.tx.api.lock.LockMode;
import java.nio.ByteBuffer;

/** Coordinates tuple-index admission, visibility, and lifecycle staging for one session. */
final class IndexedSessionTupleAccess {
  private final IndexedTransactionSession session;
  private final IndexedTuplePublishedRootProbe published;
  final IndexedTupleCurrentResolution current;
  final IndexedTupleSourceResolution source;
  private final IndexedTupleIndexStateReader stateReader = new IndexedTupleIndexStateReader();
  private final IndexedTupleProbeResult probe = new IndexedTupleProbeResult();

  IndexedSessionTupleAccess(IndexedTransactionSession owner) {
    session = owner;
    published = new IndexedTuplePublishedRootProbe(owner);
    current = new IndexedTupleCurrentResolution(owner, published);
    source = new IndexedTupleSourceResolution(owner, this, current);
  }

  StatusCode preflight(int mutations, int descriptors, int payloadBytes) {
    if (!acceptsMutations()) return StatusCode.CONFLICT;
    if (!session.sharedMutationCapacity(mutations)) return StatusCode.RESOURCE_EXHAUSTED;
    long retained = IndexedWriteWorkspaceAccounting.tuples(
        session.pendingMutations(), session.tupleIntents(), session.tupleLifecycle(),
        session.logicalRowFloors(),
        mutations, descriptors, payloadBytes);
    StatusCode status = session.ensureWriteEntries(mutations, retained);
    return status.isOk() ? session.tupleIntents().reserve(
        session.pendingMutations().count(), mutations, descriptors, payloadBytes) : status;
  }

  StatusCode preflightRelational(
      int[] lengths, int start, int scalarRows,
      int tupleMutations, int descriptors, int tuplePayloadBytes) {
    if (!acceptsMutations()) return StatusCode.CONFLICT;
    int additional = scalarRows + tupleMutations;
    if (!session.sharedMutationCapacity(additional)) return StatusCode.RESOURCE_EXHAUSTED;
    long retained = IndexedWriteWorkspaceAccounting.combined(
        session.pendingMutations(), session.tupleIntents(), session.tupleLifecycle(),
        session.logicalRowFloors(),
        lengths, start, scalarRows, tupleMutations, descriptors, tuplePayloadBytes);
    StatusCode status = session.ensureWriteEntries(additional, retained);
    if (status.isOk()) status = session.pendingMutations().reserve(lengths, start, scalarRows);
    return status.isOk() ? session.tupleIntents().reserve(
        session.pendingMutations().count() + scalarRows,
        tupleMutations, descriptors, tuplePayloadBytes) : status;
  }

  StatusCode descriptorStatus(
      long ownerId, long keyId, long schemaId, TupleShape shape) {
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
    return session.tupleIntents().descriptorStatus(ownerId, keyId, schemaId, shape);
  }

  StatusCode append(
      int operation, long ownerId, long keyId, long schemaId, TupleShape shape,
      long rowId, ByteBuffer key, int offset, int length) {
    if (!acceptsMutations()) return StatusCode.CONFLICT;
    boolean privateBuild = session.tupleLifecycle().appendsBuilding(
        ownerId, keyId, schemaId, shape);
    StatusCode status = privateBuild ? StatusCode.OK
        : IndexedTupleKeyProtection.protectExclusive(
            session, keyId, key, offset, length);
    return status.isOk() ? session.tupleIntents().append(
        operation, ownerId, keyId, schemaId, shape, rowId, key, offset, length) : status;
  }

  StatusCode preflightLifecycles(int additional) {
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
    if (!session.sharedMutationCapacity(additional)) return StatusCode.RESOURCE_EXHAUSTED;
    long retained = IndexedWriteWorkspaceAccounting.lifecycle(
        session.pendingMutations(), session.tupleIntents(), session.tupleLifecycle(),
        session.logicalRowFloors(), additional);
    StatusCode status = session.ensureWriteEntries(additional, retained);
    return status.isOk() ? session.tupleLifecycle().reserve(additional) : status;
  }

  StatusCode stageLifecycle(
      int operation, long ownerId, long keyId, long schemaId,
      long privateOwner, TupleShape shape, int cleanupEndPageId) {
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
    if (session.tupleIntents().mutationCount() != 0
        && operation != IndexedTupleIndexLifecycleBatch.PUBLISH_READY) {
      return StatusCode.CONFLICT;
    }
    if (!session.sharedMutationCapacity(1)) return StatusCode.RESOURCE_EXHAUSTED;
    long retained = IndexedWriteWorkspaceAccounting.lifecycle(
        session.pendingMutations(), session.tupleIntents(), session.tupleLifecycle(),
        session.logicalRowFloors(), 1);
    StatusCode status = session.ensureWriteEntries(1, retained);
    if (status.isOk()) status = session.acquireExclusiveKey(
        CatalogKeyspace.INDEX_ROOT_SPACE, keyId);
    return status.isOk() ? session.tupleLifecycle().append(
        operation, ownerId, keyId, schemaId, privateOwner, shape, cleanupEndPageId) : status;
  }

  StatusCode readState(long keyId, IndexedTupleIndexState result) {
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
    StatusCode status = session.protectKey(
        CatalogKeyspace.INDEX_ROOT_SPACE, keyId, LockMode.SHARED);
    return status.isOk() ? stateReader.read(session, keyId, result) : status;
  }

  StatusCode probePrefix(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
    StatusCode status = session.protectKey(
        CatalogKeyspace.INDEX_ROOT_SPACE, keyId, LockMode.SHARED);
    return status.isOk() ? published.snapshot(
        ownerId, keyId, schemaId, shape, key, offset, length, result) : status;
  }

  StatusCode resolveUnique(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
    StatusCode status = session.protectKey(
        CatalogKeyspace.INDEX_ROOT_SPACE, keyId, LockMode.SHARED);
    if (!status.isOk()) return status;
    status = session.tupleIntents().resolveUniquePrefix(
        keyId, shape, key, offset, length, false, 0, result);
    if (!status.isOk() || result.found()) return status;
    if (session.tupleIntents().deletesUniquePrefix(keyId, shape, key, offset, length)) {
      return StatusCode.OK;
    }
    status = published.snapshot(
        ownerId, keyId, schemaId, shape, key, offset, length, probe);
    return status.isOk() ? session.tupleIntents().resolveUniquePrefix(
        keyId, shape, key, offset, length,
        probe.found(), probe.logicalRowId(), result) : status;
  }

  StatusCode resolveAny(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, long excludedRowId,
      IndexedTupleProbeResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (!session.activeTransaction()) return StatusCode.CONFLICT;
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
      status = published.snapshotAfter(
          ownerId, keyId, schemaId, shape, key, offset, length, after, probe);
      if (!status.isOk() || !probe.found()) return status;
      after = probe.logicalRowId();
    } while (after == excludedRowId || session.tupleIntents().deletesPrefixRow(
        keyId, shape, key, offset, length, after));
    result.set(after);
    return StatusCode.OK;
  }

  StatusCode validateUnique(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, long rowId) {
    if (!session.activeTransaction() || rowId <= 0
        || !IndexedTupleLockKey.valid(key, offset, length)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = lockUniquePrefix(keyId, key, offset, length);
    if (status.isOk()) status = published.current(
        ownerId, keyId, schemaId, shape, key, offset, length, probe);
    return status.isOk() ? session.tupleIntents().uniquePrefixStatus(
        keyId, shape, key, offset, length, rowId,
        probe.found(), probe.logicalRowId()) : status;
  }

  StatusCode validateBuilding(
      long ownerId, long keyId, long schemaId, long privateOwner, TupleShape shape,
      ByteBuffer key, int offset, int length, long rowId) {
    if (!session.activeTransaction() || privateOwner <= 0 || rowId <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.acquireExclusiveKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId);
    if (status.isOk()) {
      status = session.table().probeTupleBuildingPrefixCurrent(
          ownerId, keyId, schemaId, privateOwner, shape, key, offset, length, probe);
      session.observeCommit(probe.observedCommitSequence());
    }
    return status.isOk() ? session.tupleIntents().appendOnlyUniquePrefixStatus(
        keyId, shape, key, offset, length, rowId,
        probe.found(), probe.logicalRowId()) : status;
  }

  private boolean acceptsMutations() {
    return session.transaction().state() == TransactionState.ACTIVE
        && (!session.tupleLifecycle().active()
            || session.tupleLifecycle().acceptsTupleMutations());
  }

  private StatusCode lockUniquePrefix(
      long keyId, ByteBuffer key, int offset, int length) {
    return IndexedTupleKeyProtection.protectExclusive(
        session, keyId, key, offset, length);
  }
}
