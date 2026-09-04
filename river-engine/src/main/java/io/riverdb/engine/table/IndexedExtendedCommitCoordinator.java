package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.wal.local.LocalWal;

/** Owns relational, hybrid, and vacuum commit strategies added above scalar mutations. */
final class IndexedExtendedCommitCoordinator {
  private final IndexedRelationalLiveCommit relational;
  private final IndexedHybridCommitGroup hybridGroup;
  private final IndexedVacuumCoordinator vacuum;

  IndexedExtendedCommitCoordinator(
      IndexedTableStore store,
      IndexedTableKernel kernel,
      LocalWal wal,
      IndexedPageSet pages,
      IndexedStorePhase phase,
      IndexedWalRecovery recovery,
      IndexedLogicalRowIdRegistry logicalRowIds,
      IndexedGroupCommitMetrics commitMetrics) {
    relational = new IndexedRelationalLiveCommit(
        store, new IndexedRelationalCommitCoordinator(
            wal, kernel, pages, logicalRowIds, commitMetrics));
    hybridGroup = new IndexedHybridCommitGroup(
        store, kernel, pages, wal, logicalRowIds, commitMetrics);
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
      IndexedPreparedLogicalCommit preparedCommit,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    return hybridGroup.commitDirect(
        preparedCommit, oldestVisibleCommitSequence, result);
  }

  StatusCode preflightHybridGroup(
      IndexedPreparedLogicalCommit[] prepared, int count,
      long oldestVisibleCommitSequence) {
    return hybridGroup.preflight(
        prepared, count, oldestVisibleCommitSequence);
  }

  StatusCode reserveHybridGroupCapacity(int required) {
    return hybridGroup.reserveMemberCapacity(required);
  }

  StatusCode appendHybridGroup(
      IndexedPreparedLogicalCommit[] prepared,
      long[] commitSequences,
      long[] committedRows,
      int count) {
    return hybridGroup.append(prepared, commitSequences, committedRows, count);
  }

  StatusCode forceHybridGroup() { return hybridGroup.force(); }
  StatusCode prepareHybridGroupPublication() { return hybridGroup.preparePublication(); }
  StatusCode installHybridGroupPublication() { return hybridGroup.installPublication(); }
  StatusCode cancelHybridGroup() { return hybridGroup.cancel(); }
  boolean hybridGroupActive() { return hybridGroup.active(); }
  boolean hybridDecisionAppended() { return hybridGroup.decisionAppended(); }
  boolean hybridDurabilityUncertain() { return hybridGroup.durabilityUncertain(); }

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
    return hybridGroup.compilationCopiedPayloadBytes();
  }
  long walCopiedPayloadBytes() {
    return relational.walCopiedPayloadBytes()
        + hybridGroup.walCopiedPayloadBytes();
  }
}
