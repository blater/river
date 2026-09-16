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
      IndexedCommitPath path,
      IndexedPreparedCommitCohortDemand demand) {
    long started = System.nanoTime();
    StatusCode status = pages.reclaimHistorical(oldestVisibleCommitSequence);
    record(path, IndexedCommitStage.PREFLIGHT_RECLAIM, started, status);
    if (!status.isOk()) {
      demand.rejectAll();
      return status;
    }
    started = System.nanoTime();
    status = kernel.reserveOperationVersions(admittedVersionOperations);
    record(path, IndexedCommitStage.PREFLIGHT_VERSION_RESERVATION, started, status);
    if (!status.isOk()) {
      demand.rejectAll();
      return status;
    }
    int records = 0;
    for (int index = 0; index < count; index++) {
      status = prepareMember(
          preparedCommits[index], index, plans, mutations, rowEnds, heapPageEnds,
          sequences, oldestVisibleCommitSequence, path, demand, records);
      if (!status.isOk()) return status;
      records += preparedCommits[index].walPlan().batchChunkCount();
    }
    started = System.nanoTime();
    status = kernel.admitOperationPublication();
    record(path, IndexedCommitStage.PREFLIGHT_OPERATION_ADMISSION, started, status);
    if (!status.isOk()) demand.rejectAll();
    return status;
  }

  private StatusCode prepareMember(
      IndexedPreparedLogicalCommit prepared, int index,
      IndexedRelationalWalPlan[] plans,
      IndexedRelationalMutationBuffer[] mutations,
      long[] rowEnds, int[] heapPageEnds, long[] sequences,
      long oldestVisibleCommitSequence, IndexedCommitPath path,
      IndexedPreparedCommitCohortDemand demand, int records) {
    if (prepared == null || !prepared.valid()) {
      demand.rejectAll();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long rowStart = kernel.operationRowCount();
    int heapPageStart = kernel.operationLastHeapPageId();
    int versionStart = kernel.operationVersionCount();
    StatusCode status = pages.beginMemberStagingAdmission(prepared);
    if (!status.isOk()) {
      demand.rejectAll();
      return status;
    }
    IndexedRelationalWalPlan plan = prepared.walPlan();
    status = compileMember(
        prepared, plan, index, sequences, oldestVisibleCommitSequence, path, records);
    if (status.isOk()) {
      plans[index] = plan;
      mutations[index] = compiler.mutation().buffer();
      rowEnds[index] = kernel.operationRowCount();
      heapPageEnds[index] = kernel.operationLastHeapPageId();
      demand.acceptMember();
      pages.endMemberStagingAdmission();
      return StatusCode.OK;
    }
    if (pages.memberCapacityPressure()
        && (status == StatusCode.RETRY || status == StatusCode.RESOURCE_EXHAUSTED)) {
      StatusCode pressure = status;
      pages.rollbackStagedMember();
      pages.endMemberStagingAdmission();
      kernel.rollbackOperationState(rowStart, heapPageStart, versionStart);
      plan.reset();
      demand.split(pressure);
      return pressure;
    }
    pages.endMemberStagingAdmission();
    demand.rejectAll();
    return status;
  }

  private StatusCode compileMember(
      IndexedPreparedLogicalCommit prepared, IndexedRelationalWalPlan plan,
      int index, long[] sequences, long oldestVisibleCommitSequence,
      IndexedCommitPath path, int records) {
    long started = System.nanoTime();
    StatusCode status = compiler.compileCumulative(
        prepared.pendingMutations(), prepared.tupleIntents(), prepared.tupleLifecycle(),
        prepared.logicalRowFloors());
    record(path, IndexedCommitStage.PREFLIGHT_COMPILE, started, status);
    if (status.isOk()) status = validateVersions(prepared);
    if (status.isOk()) status = planMember(prepared, plan, index, sequences, path);
    if (status.isOk()) status = validateLogicalRows(path);
    if (status.isOk()) status = admitWal(plan, path, records);
    if (status.isOk()) status = freezeMember(index, oldestVisibleCommitSequence, path);
    return status;
  }

  private StatusCode validateVersions(IndexedPreparedLogicalCommit prepared) {
    int actual = IndexedVersionOperation.required(compiler.mutation().buffer());
    return actual < 0 || actual != prepared.admittedVersionOperations()
        ? StatusCode.INVARIANT_BROKEN : StatusCode.OK;
  }

  private StatusCode planMember(
      IndexedPreparedLogicalCommit prepared, IndexedRelationalWalPlan plan,
      int index, long[] sequences, IndexedCommitPath path) {
    long started = System.nanoTime();
    StatusCode status = plan.planPrepared(prepared.transaction().transactionId(), sequences[index],
        compiler.mutation().buffer());
    if (status.isOk()
        && (plan.batchChunkCount() > prepared.admittedWalRecords()
            || plan.totalEncodedBytes() < 0
            || plan.totalEncodedBytes() > prepared.admittedWalBytes())) {
      status = StatusCode.INVARIANT_BROKEN;
    }
    record(path, IndexedCommitStage.PREFLIGHT_WAL_PLAN, started, status);
    return status;
  }

  private StatusCode validateLogicalRows(IndexedCommitPath path) {
    long started = System.nanoTime();
    StatusCode status = logicalRowIds.validate(compiler.mutation().buffer());
    record(path, IndexedCommitStage.PREFLIGHT_LOGICAL_ROW_ADMISSION, started, status);
    return status;
  }

  private StatusCode admitWal(
      IndexedRelationalWalPlan plan, IndexedCommitPath path, int records) {
    long started = System.nanoTime();
    StatusCode status = records > Integer.MAX_VALUE - plan.batchChunkCount()
        ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
    record(path, IndexedCommitStage.PREFLIGHT_WAL_ADMISSION, started, status);
    return status;
  }

  private StatusCode freezeMember(
      int index, long oldestVisibleCommitSequence, IndexedCommitPath path) {
    long started = System.nanoTime();
    StatusCode status = pages.freezeChangedPages(index, oldestVisibleCommitSequence);
    record(path, IndexedCommitStage.PREFLIGHT_PAGE_FREEZE, started, status);
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
