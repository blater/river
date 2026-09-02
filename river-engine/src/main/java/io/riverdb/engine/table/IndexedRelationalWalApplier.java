package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Atomically stages and publishes one completely decoded relational WAL group. */
final class IndexedRelationalWalApplier implements IndexedRelationalWalReplay {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedRelationalBaseApply base;
  private final IndexedTupleRegistryState registry;
  private final IndexedRelationalTupleApply tuples;
  private final IndexedRelationalApplyEvidence evidence;
  private final IndexedLogicalRowIdPublication logicalRowIds;
  private final IndexedPreparedCommitInstaller installer;
  private final IndexedRelationalMutationBuffer[] mutations =
      new IndexedRelationalMutationBuffer[1];
  private final long[] sequences = new long[1];
  private final long[] rowEnds = new long[1];
  private final int[] heapPageEnds = new int[1];
  private long previousRows;
  private boolean staged;

  IndexedRelationalWalApplier(
      IndexedTableKernel table, IndexedPageSet pageSet,
      IndexedLogicalRowIdRegistry logicalRowIdRegistry) {
    kernel = table;
    pages = pageSet;
    base = new IndexedRelationalBaseApply(table, pageSet);
    registry = new IndexedTupleRegistryState(table, pageSet);
    tuples = new IndexedRelationalTupleApply(table, pageSet, registry);
    evidence = new IndexedRelationalApplyEvidence(pageSet);
    logicalRowIds = new IndexedLogicalRowIdPublication(logicalRowIdRegistry);
    installer = new IndexedPreparedCommitInstaller(
        table, pageSet, logicalRowIdRegistry);
  }

  @Override
  public StatusCode apply(
      IndexedRelationalMutationBuffer mutations,
      long recordStart,
      long recordEnd,
      long commitSequence,
      long oldestVisibleCommitSequence,
      boolean recovery) {
    if (recordStart <= 0 || recordEnd <= recordStart || commitSequence <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = recovery ? logicalRowIds.recover(mutations) : StatusCode.OK;
    if (!status.isOk()) return status;
    status = stage(mutations, oldestVisibleCommitSequence);
    return status.isOk()
        ? publish(recordStart, recordEnd, commitSequence, recovery) : status;
  }

  StatusCode stage(
      IndexedRelationalMutationBuffer mutations, long oldestVisibleCommitSequence) {
    if (staged || mutations == null || !mutations.sealed()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode floorStatus = logicalRowIds.validate(mutations);
    if (!floorStatus.isOk()) return floorStatus;
    boolean floorOnly = floorOnly(mutations);
    int requiredVersions = floorOnly ? 0 : IndexedVersionOperation.required(mutations);
    if (requiredVersions < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = pages.reclaimHistorical(oldestVisibleCommitSequence);
    if (status.isOk()) status = kernel.reserveOperationVersions(requiredVersions);
    if (status.isOk() && !floorOnly) status = registry.reserve(mutations.descriptorCount());
    if (!status.isOk()) return status;
    pages.resetChanges();
    kernel.beginOperationState();
    registry.reset();
    previousRows = kernel.rowCount();
    status = pages.beginPreparedBatch();
    for (int operation = 0; status.isOk() && !floorOnly
        && operation < mutations.suboperationCount(); operation++) {
      status = evidence.expected(mutations, operation);
      if (status.isOk()) {
        status = mutations.suboperationDescriptorAt(operation) < 0
            ? base.apply(mutations, operation) : tuples.apply(mutations, operation);
      }
      if (status.isOk()) status = evidence.resulting(mutations, operation);
    }
    if (status.isOk() && !floorOnly) status = kernel.admitOperationPublication();
    if (status.isOk()) status = pages.freezeChangedPages(0, oldestVisibleCommitSequence);
    if (status.isOk()) {
      stagedMutations = mutations;
      staged = true;
      return StatusCode.OK;
    }
    finish(false);
    return status;
  }

  StatusCode publish(
      long recordStart, long recordEnd, long commitSequence, boolean recovery) {
    if (!staged || recordStart <= 0 || recordEnd <= recordStart || commitSequence <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    mutations[0] = stagedMutations;
    sequences[0] = commitSequence;
    rowEnds[0] = kernel.operationRowCount();
    heapPageEnds[0] = kernel.operationLastHeapPageId();
    StatusCode status = installer.install(
        mutations, sequences, rowEnds, heapPageEnds, 1, previousRows,
        recordStart, recordEnd, recovery);
    if (status.isOk()) status = pages.releasePreparedBatch();
    finish(status.isOk());
    return status;
  }

  void cancel() { finish(false); }

  private void finish(boolean published) {
    if (!published) {
      pages.clearStagedFlags();
      pages.cancelPreparedBatch();
    }
    pages.resetChanges();
    kernel.clearOperationVersions();
    registry.reset();
    previousRows = 0;
    staged = false;
    stagedMutations = null;
    mutations[0] = null;
    sequences[0] = 0;
    rowEnds[0] = 0;
    heapPageEnds[0] = 0;
  }

  private IndexedRelationalMutationBuffer stagedMutations;

  private static boolean floorOnly(IndexedRelationalMutationBuffer mutations) {
    return mutations != null && mutations.logicalRowFloorCount() > 0
        && mutations.suboperationCount() == 0 && mutations.mutationCount() == 0;
  }
}
