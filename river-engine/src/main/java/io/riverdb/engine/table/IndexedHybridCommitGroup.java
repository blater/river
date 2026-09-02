package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;

/** Cumulatively stages small hybrid transactions, then publishes one forced WAL cohort. */
final class IndexedHybridCommitGroup {
  private final long[] rowEnds = new long[LocalWal.MAX_PENDING_RECORDS];
  private final int[] heapPageEnds = new int[LocalWal.MAX_PENDING_RECORDS];
  private final long[] sequences = new long[LocalWal.MAX_PENDING_RECORDS];
  private final IndexedRelationalWalPlan[] plans =
      new IndexedRelationalWalPlan[LocalWal.MAX_PENDING_RECORDS];
  private final IndexedRelationalMutationBuffer[] mutations =
      new IndexedRelationalMutationBuffer[LocalWal.MAX_PENDING_RECORDS];
  private final IndexedTableStore store;
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedRelationalWalGroupAppender wal;
  private final IndexedHybridGroupPreflight preflight;
  private final IndexedHybridGroupPublication publication;
  private int count;
  private boolean prepared;
  private boolean active;
  private long groupBaseRow;

  IndexedHybridCommitGroup(
      IndexedTableStore table, IndexedTableKernel tableKernel,
      IndexedPageSet pageSet, LocalWal localWal,
      IndexedLogicalRowIdRegistry logicalRowIds) {
    store = table;
    kernel = tableKernel;
    pages = pageSet;
    wal = new IndexedRelationalWalGroupAppender(localWal);
    preflight = new IndexedHybridGroupPreflight(
        table, tableKernel, pageSet, logicalRowIds);
    publication = new IndexedHybridGroupPublication(
        table, tableKernel, pageSet, logicalRowIds);
  }

  StatusCode preflight(
      IndexedTransactionSession[] sessions, int transactionCount,
      long oldestVisibleCommitSequence) {
    if (active || sessions == null || transactionCount <= 0
        || transactionCount > sessions.length || transactionCount > plans.length
        || oldestVisibleCommitSequence < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = begin();
    if (status.isOk()) count = transactionCount;
    if (status.isOk()) status = assignSequences(transactionCount);
    if (status.isOk()) status = preflight.prepare(
        sessions, plans, mutations, rowEnds, heapPageEnds, sequences,
        transactionCount, oldestVisibleCommitSequence);
    if (status.isOk()) return StatusCode.OK;
    StatusCode cleanup = cancel();
    return cleanup.isOk() ? status : cleanup;
  }

  StatusCode append(
      IndexedTransactionSession[] sessions, long[] commitSequences, int transactionCount) {
    if (!active || count != transactionCount || sessions == null
        || commitSequences == null || transactionCount > commitSequences.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!store.phase.beginHybridEncoding()) return StatusCode.INVARIANT_BROKEN;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < count; index++) {
      IndexedTransactionSession session = sessions[index];
      long sequence = sequences[index];
      IndexedRelationalWalPlan plan = session.groupWalPlan();
      if (status.isOk()) {
        commitSequences[index] = sequence;
        session.recordGroupAppend(rowEnds[index], sequence);
      }
    }
    if (status.isOk()) status = wal.append(plans, sequences, count);
    if (!status.isOk() && (wal.appended() || indeterminate(status))) {
      if (wal.appended()) wal.fence();
      store.failed = true;
    }
    return status;
  }

  StatusCode force() {
    StatusCode status = active && store.phase.hybridEncoding()
        ? wal.force() : StatusCode.CONFLICT;
    if (status.isOk() && !store.phase.markHybridForced()) {
      status = StatusCode.INVARIANT_BROKEN;
    }
    if (!status.isOk() && wal.appended()) store.failed = true;
    return status;
  }

  StatusCode preparePublication() {
    if (!active || count <= 0 || !wal.forced() || !store.phase.hybridForced()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = publication.prepare(
        wal, mutations, sequences, rowEnds, heapPageEnds, count, groupBaseRow);
    if (!status.isOk()) {
      cancelInstalledGroup();
      return status;
    }
    status = cleanupPreparedWork();
    if (!status.isOk()) {
      cancelInstalledGroup();
      return status;
    }
    prepared = true;
    return StatusCode.OK;
  }

  StatusCode installPublication() {
    if (!active || !prepared) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = publication.install(wal);
    if (status.isOk()) finishInstalled();
    return status;
  }

  StatusCode cancel() {
    if (!active) return StatusCode.OK;
    if (wal.appended()) {
      wal.fence();
      store.failed = true;
      cancelInstalledGroup();
      return StatusCode.FENCED;
    }
    wal.reset();
    cancelUnforcedGroup();
    return StatusCode.OK;
  }

  boolean active() { return active; }

  boolean decisionAppended() { return wal.appended(); }
  long compilationCopiedPayloadBytes() { return preflight.compilationCopiedPayloadBytes(); }
  long walCopiedPayloadBytes() { return wal.copiedPayloadBytes(); }

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
    publication.reset();
    resetGroup();
  }

  private void cancelInstalledGroup() {
    store.failed = wal.appended();
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

  private static boolean indeterminate(StatusCode status) {
    return status == StatusCode.IO_FAILURE || status == StatusCode.FENCED
        || status == StatusCode.CORRUPTION || status == StatusCode.INVARIANT_BROKEN;
  }
}
