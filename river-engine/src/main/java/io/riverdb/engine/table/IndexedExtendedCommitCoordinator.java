package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.wal.local.LocalWal;

/** Owns relational, hybrid, and vacuum commit strategies added above scalar mutations. */
final class IndexedExtendedCommitCoordinator {
  private final IndexedRelationalLiveCommit relational;
  private final IndexedHybridCommitCoordinator hybrid;
  private final IndexedHybridCommitGroup hybridGroup;
  private final IndexedVacuumCoordinator vacuum;

  IndexedExtendedCommitCoordinator(
      IndexedTableStore store,
      IndexedTableKernel kernel,
      LocalWal wal,
      IndexedPageSet pages,
      IndexedStorePhase phase,
      IndexedWalRecovery recovery,
      IndexedLogicalRowIdRegistry logicalRowIds) {
    relational = new IndexedRelationalLiveCommit(
        store, new IndexedRelationalCommitCoordinator(wal, kernel, pages, logicalRowIds));
    hybrid = new IndexedHybridCommitCoordinator(store, kernel, pages, wal, logicalRowIds);
    hybridGroup = new IndexedHybridCommitGroup(
        store, kernel, pages, wal, logicalRowIds);
    vacuum = new IndexedVacuumCoordinator(wal, kernel, pages, phase, recovery);
  }

  StatusCode commitRelational(
      long transactionId,
      IndexedRelationalMutation mutations,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    return relational.apply(
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
    return hybrid.commit(
        transactionId, pending, intents, lifecycle, logicalRowFloors,
        oldestVisibleCommitSequence, result);
  }

  StatusCode preflightHybridGroup(
      IndexedTransactionSession[] sessions, int count,
      long oldestVisibleCommitSequence) {
    return hybridGroup.preflight(
        sessions, count, oldestVisibleCommitSequence);
  }

  StatusCode appendHybridGroup(
      IndexedTransactionSession[] sessions, long[] commitSequences, int count) {
    return hybridGroup.append(sessions, commitSequences, count);
  }

  StatusCode forceHybridGroup() { return hybridGroup.force(); }
  StatusCode prepareHybridGroupPublication() { return hybridGroup.preparePublication(); }
  StatusCode installHybridGroupPublication() { return hybridGroup.installPublication(); }
  StatusCode cancelHybridGroup() { return hybridGroup.cancel(); }
  boolean hybridGroupActive() { return hybridGroup.active(); }
  boolean hybridDecisionAppended() { return hybridGroup.decisionAppended(); }

  StatusCode commitVacuum(
      long transactionId,
      long commitSequence,
      long lastCommitSequence,
      WalGeneration generation,
      IndexedVacuumResult result) {
    return vacuum.commit(
        transactionId, commitSequence, lastCommitSequence, generation, result);
  }

  StatusCode vacuumStatus() { return vacuum.status(); }
  boolean vacuumFailureFences() { return vacuum.failureFences(); }
  long vacuumCopiedBytes() { return vacuum.copiedBytes(); }
  long compilationCopiedPayloadBytes() {
    return hybrid.compilationCopiedPayloadBytes()
        + hybridGroup.compilationCopiedPayloadBytes();
  }
  long walCopiedPayloadBytes() {
    return relational.walCopiedPayloadBytes()
        + hybrid.walCopiedPayloadBytes() + hybridGroup.walCopiedPayloadBytes();
  }
}
