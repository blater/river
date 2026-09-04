package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
/** Cumulatively compiles one bounded cohort before any transaction decision is appended. */
final class IndexedHybridGroupPreflight {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedHybridMutationCompiler compiler;
  private final IndexedLogicalRowIdPublication logicalRowIds;
  private final IndexedGroupCommitMetrics metrics;

  IndexedHybridGroupPreflight(
      IndexedTableStore store, IndexedTableKernel table, IndexedPageSet pageSet,
      IndexedLogicalRowIdRegistry logicalRowIdRegistry,
      IndexedGroupCommitMetrics commitMetrics) {
    kernel = table;
    pages = pageSet;
    compiler = new IndexedHybridMutationCompiler(store, table, pageSet);
    logicalRowIds = new IndexedLogicalRowIdPublication(logicalRowIdRegistry);
    metrics = commitMetrics;
  }

  StatusCode prepare(
      IndexedPreparedLogicalCommit[] preparedCommits,
      IndexedRelationalWalPlan[] plans,
      IndexedRelationalMutationBuffer[] mutations,
      long[] rowEnds,
      int[] heapPageEnds,
      long[] sequences,
      int count,
      int admittedVersionOperations,
      long oldestVisibleCommitSequence,
      IndexedCommitPath path) {
    long started = System.nanoTime();
    StatusCode status = pages.reclaimHistorical(oldestVisibleCommitSequence);
    record(path, IndexedCommitStage.PREFLIGHT_RECLAIM, started, status);
    if (status.isOk()) {
      started = System.nanoTime();
      status = kernel.reserveOperationVersions(admittedVersionOperations);
      record(path, IndexedCommitStage.PREFLIGHT_VERSION_RESERVATION, started, status);
    }
    int records = 0;
    for (int index = 0; status.isOk() && index < count; index++) {
      IndexedPreparedLogicalCommit prepared = preparedCommits[index];
      if (prepared == null || !prepared.valid()) return StatusCode.INVALID_EXTERNAL_INPUT;
      int changedBefore = pages.changedPageCount();
      started = System.nanoTime();
      status = compiler.compileCumulative(
          prepared.pendingMutations(), prepared.tupleIntents(), prepared.tupleLifecycle(),
          prepared.logicalRowFloors());
      record(path, IndexedCommitStage.PREFLIGHT_COMPILE, started, status);
      IndexedRelationalWalPlan plan = prepared.walPlan();
      if (status.isOk()) {
        int actualVersions = IndexedVersionOperation.required(compiler.mutation().buffer());
        if (actualVersions < 0 || actualVersions != prepared.admittedVersionOperations()) {
          status = StatusCode.INVARIANT_BROKEN;
        }
      }
      if (status.isOk()) {
        started = System.nanoTime();
        status = plan.planPrepared(prepared.transaction().transactionId(), sequences[index],
            compiler.mutation().buffer());
        if (status.isOk()
            && (plan.batchChunkCount() > prepared.admittedWalRecords()
                || plan.totalEncodedBytes() < 0
                || plan.totalEncodedBytes() > prepared.admittedWalBytes())) {
          status = StatusCode.INVARIANT_BROKEN;
        }
        record(path, IndexedCommitStage.PREFLIGHT_WAL_PLAN, started, status);
      }
      if (status.isOk()) {
        started = System.nanoTime();
        status = logicalRowIds.validate(compiler.mutation().buffer());
        record(path, IndexedCommitStage.PREFLIGHT_LOGICAL_ROW_ADMISSION, started, status);
      }
      if (status.isOk()) {
        started = System.nanoTime();
        if (records > Integer.MAX_VALUE - plan.batchChunkCount()) {
          status = StatusCode.RESOURCE_EXHAUSTED;
        }
        record(path, IndexedCommitStage.PREFLIGHT_WAL_ADMISSION, started, status);
      }
      if (status.isOk()) {
        started = System.nanoTime();
        int changedAfter = pages.changedPageCount();
        status = changedAfter < changedBefore
            ? StatusCode.INVARIANT_BROKEN
            : prepared.admitStagedPages(changedAfter - changedBefore);
        record(path, IndexedCommitStage.PREFLIGHT_RESOURCE_ADMISSION, started, status);
      }
      if (status.isOk()) {
        records += plan.batchChunkCount();
        plans[index] = plan;
        mutations[index] = compiler.mutation().buffer();
        rowEnds[index] = kernel.operationRowCount();
        heapPageEnds[index] = kernel.operationLastHeapPageId();
        started = System.nanoTime();
        status = pages.freezeChangedPages(index, oldestVisibleCommitSequence);
        record(path, IndexedCommitStage.PREFLIGHT_PAGE_FREEZE, started, status);
      }
    }
    if (!status.isOk()) return status;
    started = System.nanoTime();
    status = kernel.admitOperationPublication();
    record(path, IndexedCommitStage.PREFLIGHT_OPERATION_ADMISSION, started, status);
    return status;
  }

  long compilationCopiedPayloadBytes() { return compiler.copiedPayloadBytes(); }

  private void record(
      IndexedCommitPath path,
      IndexedCommitStage stage,
      long started,
      StatusCode status) {
    metrics.recordStage(
        path, stage, System.nanoTime() - started);
    if (!status.isOk()) {
      metrics.recordStageFailure(path, stage, status);
    }
  }

}
