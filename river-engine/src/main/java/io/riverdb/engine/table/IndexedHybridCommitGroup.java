package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalForceCause;

/** Stages one cohort, publishes its irrevocable decision, and completes its WAL durability. */
final class IndexedHybridCommitGroup {
  private long[] rowEnds = new long[0];
  private int[] heapPageEnds = new int[0];
  private long[] sequences = new long[0];
  private IndexedRelationalWalPlan[] plans = new IndexedRelationalWalPlan[0];
  private IndexedRelationalMutationBuffer[] mutations =
      new IndexedRelationalMutationBuffer[0];
  private final IndexedTableStore store;
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedRelationalWalGroupAppender wal;
  private final IndexedHybridGroupPreflight preflight;
  private final IndexedPreparedCommitCohortDemand cohortDemand =
      new IndexedPreparedCommitCohortDemand();
  private final IndexedHybridGroupPublication publication;
  private final IndexedGroupCommitMetrics metrics;
  private final IndexedPreparedLogicalCommit[] directPrepared =
      new IndexedPreparedLogicalCommit[1];
  private final long[] directSequence = new long[1];
  private final long[] directRows = new long[1];
  private int count;
  private boolean prepared;
  private boolean active;
  private long groupBaseRow;

  IndexedHybridCommitGroup(
      IndexedTableStore table, IndexedTableKernel tableKernel,
      IndexedPageSet pageSet, LocalWal localWal,
      IndexedLogicalRowIdRegistry logicalRowIds,
      IndexedGroupCommitMetrics commitMetrics) {
    store = table;
    kernel = tableKernel;
    pages = pageSet;
    wal = new IndexedRelationalWalGroupAppender(localWal);
    preflight = new IndexedHybridGroupPreflight(
        table, tableKernel, pageSet, logicalRowIds, commitMetrics);
    publication = new IndexedHybridGroupPublication(
        table, tableKernel, pageSet, logicalRowIds);
    metrics = commitMetrics;
  }

  StatusCode preflight(
      IndexedPreparedLogicalCommit[] preparedCommits, int transactionCount,
      long oldestVisibleCommitSequence) {
    return preflight(
        preparedCommits, transactionCount, oldestVisibleCommitSequence,
        IndexedCommitPath.SHARED_GROUP);
  }

  StatusCode commitDirect(
      IndexedPreparedLogicalCommit preparedCommit,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    if (active || preparedCommit == null || !preparedCommit.valid()
        || oldestVisibleCommitSequence < 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    directPrepared[0] = preparedCommit;
    long started = System.nanoTime();
    StatusCode status = reserveMemberCapacity(1);
    if (status.isOk()) {
      status = preflight(
          directPrepared, 1, oldestVisibleCommitSequence,
          IndexedCommitPath.DIRECT_COMMIT);
    }
    recordDirect(IndexedCommitStage.DIRECT_PREFLIGHT, started, status);
    if (!status.isOk()) return finishDirectFailure(status);

    started = System.nanoTime();
    status = append(directPrepared, directSequence, directRows, 1);
    recordDirect(IndexedCommitStage.DIRECT_APPEND, started, status);
    if (!status.isOk()) return finishDirectFailure(status);

    started = System.nanoTime();
    status = force(LocalWalForceCause.DIRECT_COMMIT);
    recordDirect(IndexedCommitStage.DIRECT_FORCE, started, status);
    if (!status.isOk()) return finishDirectFailure(status);

    started = System.nanoTime();
    status = preparePublication();
    if (status.isOk()) {
      long sequence = directSequence[0];
      long rows = directRows[0];
      status = installPublication();
      if (status.isOk()) result.set(rows, sequence);
    }
    recordDirect(IndexedCommitStage.DIRECT_PUBLICATION, started, status);
    if (!status.isOk()) return finishDirectFailure(status);
    directPrepared[0] = null;
    directSequence[0] = 0;
    directRows[0] = 0;
    return status;
  }

  private StatusCode preflight(
      IndexedPreparedLogicalCommit[] preparedCommits, int transactionCount,
      long oldestVisibleCommitSequence,
      IndexedCommitPath path) {
    if (active || preparedCommits == null || transactionCount <= 0
        || transactionCount > preparedCommits.length || oldestVisibleCommitSequence < 0
        || path == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = cohortDemand.measure(preparedCommits, transactionCount);
    if (status.isOk()) {
      status = store.admitDurableVersionOperations(cohortDemand.versionOperations());
    }
    if (status.isOk() && plans.length < transactionCount) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) status = begin();
    if (status.isOk()) count = transactionCount;
    if (status.isOk()) status = assignSequences(transactionCount);
    if (status.isOk()) status = preflight.prepare(
        preparedCommits, plans, mutations, rowEnds, heapPageEnds, sequences,
        transactionCount, cohortDemand.versionOperations(),
        oldestVisibleCommitSequence, path);
    if (status.isOk()) return StatusCode.OK;
    StatusCode cleanup = cancel();
    return cleanup.isOk() ? status : cleanup;
  }

  StatusCode reserveMemberCapacity(int required) {
    StatusCode status = ensureMemberCapacity(required);
    return status.isOk() ? wal.reserveTransactionCapacity(required) : status;
  }

  StatusCode append(
      IndexedPreparedLogicalCommit[] preparedCommits,
      long[] commitSequences,
      long[] committedRows,
      int transactionCount) {
    if (!active || count != transactionCount || preparedCommits == null
        || commitSequences == null || committedRows == null
        || transactionCount > commitSequences.length
        || transactionCount > committedRows.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!store.phase.beginHybridEncoding()) return StatusCode.INVARIANT_BROKEN;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < count; index++) {
      long sequence = sequences[index];
      IndexedRelationalWalPlan plan = preparedCommits[index].walPlan();
      if (status.isOk()) {
        commitSequences[index] = sequence;
        committedRows[index] = rowEnds[index];
      }
    }
    if (status.isOk()) status = wal.append(plans, sequences, count);
    if (!status.isOk() && wal.storageMayHaveChanged()) {
      wal.fence();
      store.failed = true;
    }
    return status;
  }

  StatusCode force() { return force(LocalWalForceCause.SHARED_GROUP); }

  private StatusCode force(LocalWalForceCause cause) {
    StatusCode status = active && store.phase.hybridEncoding()
        ? wal.force(cause) : StatusCode.CONFLICT;
    if (status.isOk() && !store.phase.markHybridForced()) {
      status = StatusCode.INVARIANT_BROKEN;
    }
    if (!status.isOk() && wal.appended()) store.failed = true;
    return status;
  }

  StatusCode preparePublication() {
    if (!active || count <= 0 || !wal.appended() || !store.phase.hybridEncoding()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = publication.prepare(
        wal, mutations, sequences, rowEnds, heapPageEnds, count, groupBaseRow);
    if (!status.isOk()) {
      fenceInstalledGroup();
      return status;
    }
    // Keep publication pins until force: cache eviction must never write these pages first.
    prepared = true;
    return StatusCode.OK;
  }

  StatusCode installPublication() {
    if (!active || !prepared) return StatusCode.INVALID_EXTERNAL_INPUT;
    store.pendingDurabilitySequence = sequences[0];
    StatusCode status = publication.install(wal);
    if (status.isOk()) {
      if (wal.forced()) status = completeDurability();
    } else {
      fenceInstalledGroup();
    }
    return status;
  }

  StatusCode completeDurability() {
    if (!active || !prepared || !wal.forced()) return StatusCode.INVARIANT_BROKEN;
    StatusCode status = cleanupPreparedWork();
    if (status.isOk()) status = wal.release();
    if (!status.isOk()) {
      fenceInstalledGroup();
      return status;
    }
    store.pendingDurabilitySequence = 0;
    finishInstalled();
    return StatusCode.OK;
  }

  StatusCode cancel() {
    if (!active) return StatusCode.OK;
    if (wal.storageMayHaveChanged()) {
      fenceInstalledGroup();
      return StatusCode.FENCED;
    }
    wal.reset();
    cancelUnforcedGroup();
    return StatusCode.OK;
  }

  boolean active() { return active; }

  boolean decisionAppended() { return wal.appended(); }
  boolean durabilityUncertain() { return wal.storageMayHaveChanged(); }
  long compilationCopiedPayloadBytes() { return preflight.compilationCopiedPayloadBytes(); }
  long walCopiedPayloadBytes() { return wal.copiedPayloadBytes(); }

  private StatusCode finishDirectFailure(StatusCode failure) {
    boolean decisionAppended = durabilityUncertain();
    StatusCode cleanup = cancel();
    directPrepared[0] = null;
    directSequence[0] = 0;
    directRows[0] = 0;
    if (decisionAppended && !indeterminate(failure)) return StatusCode.FENCED;
    return failure.isOk() ? cleanup : failure;
  }

  private void recordDirect(
      IndexedCommitStage stage, long started, StatusCode status) {
    metrics.recordStage(
        IndexedCommitPath.DIRECT_COMMIT, stage, System.nanoTime() - started);
    if (!status.isOk()) {
      metrics.recordStageFailure(IndexedCommitPath.DIRECT_COMMIT, stage, status);
    }
  }

  private StatusCode begin() {
    StatusCode status = store.admission();
    if (!status.isOk()) return status;
    if (!store.phase.beginHybridPreflight()) return StatusCode.RESOURCE_EXHAUSTED;
    active = true;
    pages.resetChanges();
    kernel.beginOperationState();
    groupBaseRow = kernel.rowCount();
    StatusCode preparedPages = pages.beginPreparedBatch();
    if (!preparedPages.isOk()) store.phase.reset();
    if (!preparedPages.isOk()) active = false;
    return preparedPages;
  }

  private void cancelUnforcedGroup() {
    pages.clearStagedFlags();
    pages.cancelPreparedBatch();
    pages.resetChanges();
    kernel.clearOperationVersions();
    publication.reset();
    resetGroup();
  }

  private void fenceInstalledGroup() {
    wal.fence();
    store.failed = true;
    publication.reset();
    store.phase.reset();
    resetGroup();
  }

  private StatusCode cleanupPreparedWork() {
    StatusCode status = pages.releasePreparedBatch();
    pages.resetChanges();
    kernel.clearOperationVersions();
    if (!status.isOk()) store.failed = true;
    return status;
  }

  private void finishInstalled() {
    store.phase.reset();
    resetGroup();
  }

  private void resetGroup() {
    if (store.phase.commitGroupActive() && !wal.appended()) store.phase.reset();
    for (int index = 0; index < count; index++) {
      rowEnds[index] = 0;
      heapPageEnds[index] = 0;
      sequences[index] = 0;
      plans[index] = null;
      mutations[index] = null;
    }
    count = 0;
    groupBaseRow = 0;
    prepared = false;
    active = false;
  }

  private StatusCode assignSequences(int transactionCount) {
    long sequence = store.nextCommitSequence();
    for (int index = 0; index < transactionCount; index++) {
      if (sequence <= 0) return StatusCode.RESOURCE_EXHAUSTED;
      sequences[index] = sequence;
      sequence = sequence == Long.MAX_VALUE ? 0 : sequence + 1;
    }
    return StatusCode.OK;
  }

  private StatusCode ensureMemberCapacity(int required) {
    if (plans.length >= required) return StatusCode.OK;
    try {
      rowEnds = java.util.Arrays.copyOf(rowEnds, required);
      heapPageEnds = java.util.Arrays.copyOf(heapPageEnds, required);
      sequences = java.util.Arrays.copyOf(sequences, required);
      plans = java.util.Arrays.copyOf(plans, required);
      mutations = java.util.Arrays.copyOf(mutations, required);
      return StatusCode.OK;
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static boolean indeterminate(StatusCode status) {
    return status == StatusCode.IO_FAILURE || status == StatusCode.FENCED
        || status == StatusCode.CORRUPTION || status == StatusCode.INVARIANT_BROKEN;
  }
}
