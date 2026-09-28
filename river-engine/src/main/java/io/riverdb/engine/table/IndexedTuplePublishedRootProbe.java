package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import java.nio.ByteBuffer;

/** Selects the transaction-private BUILDING root or the ordinary READY root for tuple probes. */
final class IndexedTuplePublishedRootProbe {
  private final IndexedTransactionSession session;

  IndexedTuplePublishedRootProbe(IndexedTransactionSession owner) { session = owner; }

  StatusCode snapshot(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return snapshotMatching(
        ownerId, keyId, schemaId, shape, key, offset, length,
        null, 0, result);
  }

  StatusCode snapshotMatching(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length,
      IndexedTupleIntentJournal intents, long excludedRowId,
      IndexedTupleProbeResult result) {
    long privateOwner = privateOwner(ownerId, keyId, schemaId, shape);
    StatusCode status = privateOwner > 0
        ? buildingMatching(
            ownerId, keyId, schemaId, privateOwner,
            shape, shape, key, offset, length, intents, excludedRowId, result)
        : session.table().probeTuplePrefixMatchingAt(
            session.visibleCommitSequence(), ownerId, keyId, schemaId,
            shape, shape, key, offset, length, intents, excludedRowId, result);
    session.observeCommit(result.observedCommitSequence());
    return status;
  }

  StatusCode current(
      long ownerId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, IndexedTupleProbeResult result) {
    return currentMatching(
        ownerId, keyId, schemaId, shape, shape,
        key, offset, length, null, 0, result);
  }

  StatusCode currentMatching(
      long ownerId, long keyId, long schemaId,
      TupleShape indexShape, TupleShape prefixShape,
      ByteBuffer key, int offset, int length,
      IndexedTupleIntentJournal intents, long excludedRowId,
      IndexedTupleProbeResult result) {
    long privateOwner = privateOwner(ownerId, keyId, schemaId, indexShape);
    StatusCode status = privateOwner > 0
        ? buildingMatching(
            ownerId, keyId, schemaId, privateOwner,
            indexShape, prefixShape, key, offset, length,
            intents, excludedRowId, result)
        : session.table().probeTuplePrefixMatchingCurrent(
            ownerId, keyId, schemaId, indexShape, prefixShape,
            key, offset, length, intents, excludedRowId, result);
    session.observeCommit(result.observedCommitSequence());
    return status;
  }

  private StatusCode buildingMatching(
      long ownerId, long keyId, long schemaId, long privateOwner,
      TupleShape indexShape, TupleShape prefixShape,
      ByteBuffer key, int offset, int length,
      IndexedTupleIntentJournal intents, long excludedRowId,
      IndexedTupleProbeResult result) {
    return session.table().probeTupleBuildingPrefixMatchingCurrent(
        ownerId, keyId, schemaId, privateOwner,
        indexShape, prefixShape, key, offset, length,
        intents, excludedRowId, result);
  }

  private long privateOwner(
      long ownerId, long keyId, long schemaId, TupleShape shape) {
    return session.tupleLifecycle().publishingPrivateOwner(
        ownerId, keyId, schemaId, shape);
  }
}
