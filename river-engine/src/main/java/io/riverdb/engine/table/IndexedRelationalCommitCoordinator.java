package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;

/** Reserves WAL and page work before one forced grouped relational publication. */
final class IndexedRelationalCommitCoordinator {
  private final IndexedRelationalWalPlan plan = new IndexedRelationalWalPlan();
  private final IndexedRelationalWalCommitter wal;
  private final IndexedRelationalWalApplier applier;
  private boolean failureFences;

  IndexedRelationalCommitCoordinator(
      LocalWal localWal, IndexedTableKernel kernel, IndexedPageSet pages,
      IndexedLogicalRowIdRegistry logicalRowIds,
      IndexedGroupCommitMetrics commitMetrics) {
    wal = new IndexedRelationalWalCommitter(localWal, commitMetrics);
    applier = new IndexedRelationalWalApplier(kernel, pages, logicalRowIds);
  }

  StatusCode commit(
      long transactionId,
      long operationId,
      long commitSequence,
      long oldestVisibleCommitSequence,
      IndexedRelationalMutationBuffer mutations) {
    failureFences = false;
    StatusCode status = plan.plan(transactionId, operationId, mutations);
    if (status.isOk()) status = applier.stage(mutations, oldestVisibleCommitSequence);
    if (status.isOk()) status = wal.prepare(plan, commitSequence);
    if (!status.isOk()) return cancelPrepared(status);
    status = wal.forcePrepared();
    if (!status.isOk()) {
      applier.cancel();
      if (wal.appended()) {
        failureFences = true;
        return status;
      }
      StatusCode cancel = wal.cancelPrepared();
      return cancel.isOk() ? status : cancel;
    }
    long publicationStarted = System.nanoTime();
    status = applier.publish(wal.recordStart(), wal.recordEnd(), commitSequence, false);
    metrics().recordStage(
        IndexedCommitPath.DIRECT_COMMIT,
        IndexedCommitStage.DIRECT_PUBLICATION,
        System.nanoTime() - publicationStarted);
    StatusCode release = wal.releaseForced();
    if (!status.isOk() || !release.isOk()) failureFences = true;
    return status.isOk() ? release : status;
  }

  boolean failureFences() { return failureFences; }
  long walCopiedPayloadBytes() { return wal.copiedPayloadBytes(); }

  private IndexedGroupCommitMetrics metrics() { return wal.metrics(); }

  private StatusCode cancelPrepared(StatusCode failure) {
    applier.cancel();
    StatusCode cancel = wal.cancelPrepared();
    return cancel.isOk() || cancel == StatusCode.CONFLICT ? failure : cancel;
  }
}
