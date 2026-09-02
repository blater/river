package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.wal.local.LocalWal;
import java.nio.ByteBuffer;

/** Relational commit and tuple-probe surface inherited by the scalar page store. */
abstract class IndexedRelationalStoreAccess {
  private IndexedRelationalStoreServices services;

  final void initializeRelationalServices(
      IndexedTableStore store,
      IndexedTableKernel kernel,
      LocalWal wal,
      IndexedPageSet pages,
      IndexedStorePhase phase,
      IndexedWalRecovery recovery,
      IndexedLogicalRowIdRegistry logicalRowIds) {
    services = new IndexedRelationalStoreServices(
        store, kernel, wal, pages, phase, recovery, logicalRowIds);
  }

  /** Forces and atomically publishes one preflighted base-and-index mutation group. */
  StatusCode commitRelational(
      long transactionId,
      IndexedRelationalMutation mutations,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    return relationalServices().commitRelational(
        transactionId, mutations, oldestVisibleCommitSequence, result);
  }

  StatusCode commitHybrid(
      long transactionId,
      PendingMutationBuffer pending,
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle,
      IndexedLogicalRowIdFloors logicalRowFloors,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    return relationalServices().commitHybrid(
        transactionId, pending, intents, lifecycle, logicalRowFloors,
        oldestVisibleCommitSequence, result);
  }

  StatusCode probeTuplePrefixAt(
      long visibleCommitSequence,
      long ownerObjectId,
      long keyId,
      long schemaId,
      TupleShape shape,
      ByteBuffer key,
      int offset,
      int length,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = relationalAdmission();
    return status.isOk() ? relationalServices().probe(
        visibleCommitSequence, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, result) : status;
  }

  StatusCode probeTuplePrefixAfterAt(
      long visibleCommitSequence,
      long ownerObjectId,
      long keyId,
      long schemaId,
      TupleShape shape,
      ByteBuffer key,
      int offset,
      int length,
      long afterLogicalRowId,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = relationalAdmission();
    return status.isOk() ? relationalServices().probeAfter(
        visibleCommitSequence, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, afterLogicalRowId, result) : status;
  }

  StatusCode probeTuplePrefixCurrent(
      long ownerObjectId,
      long keyId,
      long schemaId,
      TupleShape shape,
      ByteBuffer key,
      int offset,
      int length,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = relationalAdmission();
    long current = currentCommitSequence();
    return status.isOk() ? relationalServices().probe(
        current, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, result) : status;
  }

  StatusCode probeTuplePrefixAfterCurrent(
      long ownerObjectId, long keyId, long schemaId, TupleShape shape,
      ByteBuffer key, int offset, int length, long afterLogicalRowId,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = relationalAdmission();
    long current = currentCommitSequence();
    return status.isOk() ? relationalServices().probeAfter(
        current, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, afterLogicalRowId, result) : status;
  }

  StatusCode probeTupleBuildingPrefixCurrent(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      TupleShape shape, ByteBuffer key, int offset, int length,
      IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = relationalAdmission();
    long current = currentCommitSequence();
    return status.isOk() ? relationalServices().probeBuilding(
        current, ownerObjectId, keyId, schemaId, privateOwner,
        shape, key, offset, length, result) : status;
  }

  StatusCode probeTupleBuildingPrefixAfterCurrent(
      long ownerObjectId, long keyId, long schemaId, long privateOwner,
      TupleShape shape, ByteBuffer key, int offset, int length,
      long afterLogicalRowId, IndexedTupleProbeResult result) {
    result.reset();
    StatusCode status = relationalAdmission();
    long current = currentCommitSequence();
    return status.isOk() ? relationalServices().probeBuildingAfter(
        current, ownerObjectId, keyId, schemaId, privateOwner,
        shape, key, offset, length, afterLogicalRowId, result) : status;
  }

  StatusCode beginTupleScanAt(
      long visibleCommitSequence, long ownerObjectId, long keyId, long schemaId,
      long privateOwner,
      TupleShape shape, io.riverdb.storage.btree.TupleBTreeScanBounds bounds,
      IndexedTupleIntentJournal intents, IndexedTupleScanCursor cursor) {
    StatusCode status = relationalAdmission();
    return status.isOk() ? relationalServices().beginTupleScan(
        visibleCommitSequence, currentCommitSequence(), ownerObjectId, keyId, schemaId,
        privateOwner, shape, bounds, intents, cursor) : status;
  }

  StatusCode nextTupleScan(
      IndexedTupleScanCursor cursor, IndexedTupleIntentJournal intents,
      IndexedTupleScanResult result) {
    StatusCode status = relationalAdmission();
    return status.isOk()
        ? relationalServices().nextTupleScan(cursor, intents, result) : status;
  }

  StatusCode closeTupleScan(IndexedTupleScanCursor cursor) {
    return relationalServices().closeTupleScan(cursor);
  }

  final IndexedRelationalStoreServices relationalServices() { return services; }
  abstract long currentCommitSequence();
  abstract StatusCode relationalAdmission();
}
