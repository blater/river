package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;
/** Cumulatively compiles one bounded cohort before any transaction decision is appended. */
final class IndexedHybridGroupPreflight {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedHybridMutationCompiler compiler;
  private final IndexedLogicalRowIdPublication logicalRowIds;

  IndexedHybridGroupPreflight(
      IndexedTableStore store, IndexedTableKernel table, IndexedPageSet pageSet,
      IndexedLogicalRowIdRegistry logicalRowIdRegistry) {
    kernel = table;
    pages = pageSet;
    compiler = new IndexedHybridMutationCompiler(store, table, pageSet);
    logicalRowIds = new IndexedLogicalRowIdPublication(logicalRowIdRegistry);
  }

  StatusCode prepare(
      IndexedTransactionSession[] sessions,
      IndexedRelationalWalPlan[] plans,
      IndexedRelationalMutationBuffer[] mutations,
      long[] rowEnds,
      int[] heapPageEnds,
      long[] sequences,
      int count,
      long oldestVisibleCommitSequence) {
    StatusCode status = pages.reclaimHistorical(oldestVisibleCommitSequence);
    if (status.isOk()) status = reserveVersions(sessions, count);
    int records = 0;
    for (int index = 0; status.isOk() && index < count; index++) {
      IndexedTransactionSession session = sessions[index];
      status = compiler.compileCumulative(
          session.pendingMutations(), session.tupleIntents(), session.tupleLifecycle(),
          session.logicalRowFloors());
      IndexedRelationalWalPlan plan = session.groupWalPlan();
      if (status.isOk()) {
        status = plan.plan(session.groupTransaction().transactionId(), sequences[index],
            compiler.mutation().buffer());
      }
      if (status.isOk()) {
        status = logicalRowIds.validate(compiler.mutation().buffer());
      }
      if (status.isOk() && (plan.hasMoreBatches()
          || records > LocalWal.MAX_PENDING_RECORDS - plan.batchChunkCount())) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      }
      if (status.isOk()) {
        records += plan.batchChunkCount();
        plans[index] = plan;
        mutations[index] = compiler.mutation().buffer();
        rowEnds[index] = kernel.operationRowCount();
        heapPageEnds[index] = kernel.operationLastHeapPageId();
        status = pages.freezeChangedPages(index, oldestVisibleCommitSequence);
      }
    }
    return status.isOk() ? kernel.admitOperationPublication() : status;
  }

  long compilationCopiedPayloadBytes() { return compiler.copiedPayloadBytes(); }

  private StatusCode reserveVersions(IndexedTransactionSession[] sessions, int count) {
    int required = 0;
    for (int index = 0; index < count; index++) {
      IndexedTransactionSession session = sessions[index];
      if (session == null || session.tupleLifecycle().active()) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int additional = IndexedVersionOperation.required(
          session.pendingMutations().count(), session.tupleIntents().descriptorCount());
      if (additional < 0 || required > Integer.MAX_VALUE - additional) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      required += additional;
    }
    return kernel.reserveOperationVersions(required);
  }
}
