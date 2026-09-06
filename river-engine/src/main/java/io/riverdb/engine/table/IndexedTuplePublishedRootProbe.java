package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Selects the transaction-private BUILDING root or the ordinary READY root for exact probes. */
final class IndexedTuplePublishedRootProbe {
  private final IndexedTransactionSession session;

  IndexedTuplePublishedRootProbe(IndexedTransactionSession owner) { session = owner; }

  StatusCode snapshot(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return snapshotAfter(
        ownerId, keyId, schemaId, shape, key, offset, length, 0, result);
  }

  StatusCode snapshotAfter(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, long afterRowId,
      IndexedTupleProbeResult result) {
    long privateOwner = privateOwner(ownerId, keyId, schemaId, shape);
    StatusCode status = privateOwner > 0
        ? buildingAfter(
            ownerId, keyId, schemaId, privateOwner,
            shape, key, offset, length, afterRowId, result)
        : session.table().probeTuplePrefixAfterAt(
            session.visibleCommitSequence(), ownerId, keyId, schemaId,
            shape, key, offset, length, afterRowId, result);
    session.observeCommit(result.observedCommitSequence());
    return status;
  }

  StatusCode current(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return currentAfter(
        ownerId, keyId, schemaId, shape, key, offset, length, 0, result);
  }

  StatusCode currentAfter(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, long afterRowId,
      IndexedTupleProbeResult result) {
    long privateOwner = privateOwner(ownerId, keyId, schemaId, shape);
    StatusCode status = privateOwner > 0
        ? buildingAfter(
            ownerId, keyId, schemaId, privateOwner,
            shape, key, offset, length, afterRowId, result)
        : session.table().probeTuplePrefixAfterCurrent(
            ownerId, keyId, schemaId, shape,
            key, offset, length, afterRowId, result);
    session.observeCommit(result.observedCommitSequence());
    return status;
  }

  private StatusCode buildingAfter(
      long ownerId, long keyId, long schemaId, long privateOwner, TupleShape shape,
      ByteBuffer key, int offset, int length, long afterRowId,
      IndexedTupleProbeResult result) {
    return session.table().probeTupleBuildingPrefixAfterCurrent(
        ownerId, keyId, schemaId, privateOwner,
        shape, key, offset, length, afterRowId, result);
  }

  private long privateOwner(
      long ownerId, long keyId, long schemaId, TupleShape shape) {
    return session.tupleLifecycle().publishingPrivateOwner(
        ownerId, keyId, schemaId, shape);
  }
}
