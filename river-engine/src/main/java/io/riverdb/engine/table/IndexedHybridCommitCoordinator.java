package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;

/** Derives, forces, and atomically publishes one scalar-and-tuple transaction. */
final class IndexedHybridCommitCoordinator {
  private final IndexedTableStore store;
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedHybridMutationCompiler compiler;
  private final IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
  private final IndexedRelationalMutationBuffer[] mutations =
      new IndexedRelationalMutationBuffer[1];
  private final long[] sequences = new long[1];
  private final long[] rowEnds = new long[1];
  private final int[] heapPageEnds = new int[1];
  private final IndexedRelationalWalCommitter wal;
  private final IndexedLogicalRowIdPublication logicalRowIds;
  private final IndexedPreparedCommitInstaller installer;
  private IndexedRelationalMutationBuffer stagedMutations;
  private boolean floorOnly;
  private long sequence;
  private long previousRows;

  IndexedHybridCommitCoordinator(
      IndexedTableStore table, IndexedTableKernel tableKernel,
      IndexedPageSet pageSet, LocalWal localWal,
      IndexedLogicalRowIdRegistry logicalRowIdRegistry) {
    store = table;
    kernel = tableKernel;
    pages = pageSet;
    compiler = new IndexedHybridMutationCompiler(table, tableKernel, pageSet);
    wal = new IndexedRelationalWalCommitter(localWal);
    logicalRowIds = new IndexedLogicalRowIdPublication(logicalRowIdRegistry);
    installer = new IndexedPreparedCommitInstaller(
        tableKernel, pageSet, logicalRowIdRegistry);
  }

  StatusCode commit(
      long transactionId, PendingMutationBuffer pending,
      IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle,
      IndexedLogicalRowIdFloors floors,
      long oldestVisibleCommitSequence,
      IndexedCommitResult result) {
    if (transactionId <= 1 || pending == null || intents == null || result == null
        || oldestVisibleCommitSequence < 0
        || floors == null || !hasWork(pending, intents, lifecycle, floors)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = begin();
    if (!status.isOk()) return status;
    status = stage(
        pending, intents, lifecycle, floors, oldestVisibleCommitSequence);
    if (!status.isOk()) return cancelBeforeForce(status);
    status = prepareWal(transactionId);
    if (!status.isOk()) return cancelBeforeForce(status);
    status = wal.forcePrepared();
    if (!status.isOk()) return cancelAfterForceFailure(status);
    status = publishStaged();
    finishStaging(status.isOk());
    StatusCode release = wal.releaseForced();
    if (!status.isOk() || !release.isOk()) store.failed = true;
    if (status.isOk() && release.isOk()) {
      store.lastCommitSequence = sequence;
      result.set(0, sequence);
    }
    return status.isOk() ? release : status;
  }

  private StatusCode begin() {
    StatusCode status = store.admission();
    if (!status.isOk()) return status;
    if (!store.phase.beginStaged()) return StatusCode.CONFLICT;
    sequence = store.nextCommitSequence();
    previousRows = kernel.rowCount();
    pages.resetChanges();
    kernel.beginOperationState();
    status = pages.beginPreparedBatch();
    if (!status.isOk()) store.phase.reset();
    return status;
  }

  private StatusCode stage(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors,
      long oldestVisibleCommitSequence) {
    StatusCode status = pages.reclaimHistorical(oldestVisibleCommitSequence);
    if (status.isOk()) status = compiler.compile(pending, intents, lifecycle, floors);
    if (!status.isOk()) return status;
    stagedMutations = compiler.mutation().buffer();
    status = logicalRowIds.validate(stagedMutations);
    if (!status.isOk()) return status;
    floorOnly = stagedMutations.suboperationCount() == 0;
    if (!floorOnly) status = kernel.admitOperationPublication();
    return status.isOk()
        ? pages.freezeChangedPages(0, oldestVisibleCommitSequence) : status;
  }

  private StatusCode prepareWal(long transactionId) {
    StatusCode status = plan.plan(
        transactionId, sequence, compiler.mutation().buffer());
    return status.isOk() ? wal.prepare(plan, sequence) : status;
  }

  private StatusCode publishStaged() {
    sequences[0] = sequence;
    mutations[0] = stagedMutations;
    rowEnds[0] = kernel.operationRowCount();
    heapPageEnds[0] = kernel.operationLastHeapPageId();
    StatusCode status = installer.install(
        mutations, sequences, rowEnds, heapPageEnds, 1, previousRows,
        wal.recordStart(), wal.recordEnd(), false);
    if (status.isOk()) status = pages.releasePreparedBatch();
    return status;
  }

  private StatusCode cancelBeforeForce(StatusCode failure) {
    if (wal.appended()) {
      store.failed = true;
      finishStaging(false);
      return failure;
    }
    if (!wal.forced()) wal.cancelPrepared();
    finishStaging(false);
    return failure;
  }

  private StatusCode cancelAfterForceFailure(StatusCode failure) {
    if (wal.appended()) store.failed = true;
    else wal.cancelPrepared();
    finishStaging(false);
    return failure;
  }

  private void finishStaging(boolean published) {
    if (!published) {
      pages.clearStagedFlags();
      pages.cancelPreparedBatch();
    }
    pages.resetChanges();
    kernel.clearOperationVersions();
    stagedMutations = null;
    floorOnly = false;
    sequences[0] = 0;
    rowEnds[0] = 0;
    heapPageEnds[0] = 0;
    mutations[0] = null;
    if (store.phase.operationActive()) store.phase.reset();
  }

  private static boolean hasWork(
      PendingMutationBuffer pending, IndexedTupleIntentJournal intents,
      IndexedTupleIndexLifecycleBatch lifecycle, IndexedLogicalRowIdFloors floors) {
    return pending.count() > 0 || intents.mutationCount() > 0
        || lifecycle != null && lifecycle.active() || floors.count() > 0;
  }

  long compilationCopiedPayloadBytes() { return compiler.copiedPayloadBytes(); }
  long walCopiedPayloadBytes() { return wal.copiedPayloadBytes(); }
}
