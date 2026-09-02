package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.wal.local.LocalWal;
import java.nio.ByteBuffer;

/** Retained relational commit and tuple-probe services for one indexed store. */
final class IndexedRelationalStoreServices {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedExtendedCommitCoordinator commits;
  private final IndexedTuplePrefixProbe probes;

  IndexedRelationalStoreServices(
      IndexedTableStore store,
      IndexedTableKernel kernel,
      LocalWal wal,
      IndexedPageSet pages,
      IndexedStorePhase phase,
      IndexedWalRecovery recovery,
      IndexedLogicalRowIdRegistry logicalRowIds) {
    this.kernel = kernel;
    this.pages = pages;
    commits = new IndexedExtendedCommitCoordinator(
        store, kernel, wal, pages, phase, recovery, logicalRowIds);
    probes = new IndexedTuplePrefixProbe(kernel, pages);
  }

  StatusCode commitRelational(
      long transactionId, IndexedRelationalMutation mutations,
      long oldestVisibleCommitSequence, IndexedCommitResult result) {
    return commits.commitRelational(
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
    return commits.commitHybrid(
        transactionId, pending, intents, lifecycle, logicalRowFloors,
        oldestVisibleCommitSequence, result);
  }

  StatusCode preflightHybridGroup(
      IndexedTransactionSession[] sessions, int count,
      long oldestVisibleCommitSequence) {
    return commits.preflightHybridGroup(
        sessions, count, oldestVisibleCommitSequence);
  }

  StatusCode appendHybridGroup(
      IndexedTransactionSession[] sessions, long[] commitSequences, int count) {
    return commits.appendHybridGroup(sessions, commitSequences, count);
  }

  StatusCode forceHybridGroup() { return commits.forceHybridGroup(); }
  StatusCode prepareHybridGroupPublication() {
    return commits.prepareHybridGroupPublication();
  }

  StatusCode installHybridGroupPublication() {
    return commits.installHybridGroupPublication();
  }

  StatusCode cancelHybridGroup() { return commits.cancelHybridGroup(); }
  boolean hybridGroupActive() { return commits.hybridGroupActive(); }
  boolean hybridDecisionAppended() { return commits.hybridDecisionAppended(); }

  StatusCode commitVacuum(
      long transactionId,
      long commitSequence,
      long lastCommitSequence,
      WalGeneration generation,
      IndexedVacuumResult result) {
    return commits.commitVacuum(
        transactionId, commitSequence, lastCommitSequence, generation, result);
  }

  StatusCode probe(
      long visibleCommitSequence,
      long ownerObjectId,
      long keyId,
      long schemaId,
      TupleShape shape,
      ByteBuffer key,
      int offset,
      int length,
      IndexedTupleProbeResult result) {
    return probes.probe(
        visibleCommitSequence, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, result);
  }

  StatusCode probeAfter(
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
    return probes.probeAfter(
        visibleCommitSequence, ownerObjectId, keyId, schemaId,
        shape, key, offset, length, afterLogicalRowId, result);
  }

  StatusCode probeBuilding(
      long current, long ownerObjectId, long keyId, long schemaId, long privateOwner,
      TupleShape shape, ByteBuffer key, int offset, int length,
      IndexedTupleProbeResult result) {
    return probes.probeBuilding(
        current, ownerObjectId, keyId, schemaId, privateOwner,
        shape, key, offset, length, result);
  }

  StatusCode probeBuildingAfter(
      long current, long ownerObjectId, long keyId, long schemaId, long privateOwner,
      TupleShape shape, ByteBuffer key, int offset, int length,
      long afterLogicalRowId, IndexedTupleProbeResult result) {
    return probes.probeBuildingAfter(
        current, ownerObjectId, keyId, schemaId, privateOwner,
        shape, key, offset, length, afterLogicalRowId, result);
  }

  StatusCode beginTupleScan(
      long visible, long current, long ownerObjectId, long keyId, long schemaId,
      long privateOwner,
      TupleShape shape, io.riverdb.storage.btree.TupleBTreeScanBounds bounds,
      IndexedTupleIntentJournal intents, IndexedTupleScanCursor cursor) {
    return cursor.open(
        kernel, pages, visible, current, ownerObjectId, keyId, schemaId,
        privateOwner, shape, bounds, intents);
  }

  StatusCode nextTupleScan(
      IndexedTupleScanCursor cursor, IndexedTupleIntentJournal intents,
      IndexedTupleScanResult result) {
    return cursor.next(intents, result);
  }

  StatusCode closeTupleScan(IndexedTupleScanCursor cursor) { return cursor.close(); }

  StatusCode vacuumStatus() { return commits.vacuumStatus(); }
  boolean vacuumFailureFences() { return commits.vacuumFailureFences(); }
  long vacuumCopiedBytes() { return commits.vacuumCopiedBytes(); }
  long compilationCopiedPayloadBytes() {
    return commits.compilationCopiedPayloadBytes();
  }
  long walCopiedPayloadBytes() { return commits.walCopiedPayloadBytes(); }
}
