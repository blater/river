package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Writer-owned completion of published cohorts after their WAL force. */
final class IndexedCommitDurability {
  private final IndexedGroupCommitBatch batch;
  private final IndexedGroupCommitMetrics metrics;
  private final IndexedDurabilityCohortRing pending;
  private final IndexedTable table;

  IndexedCommitDurability(
      IndexedGroupCommitBatch batch, IndexedGroupCommitMetrics metrics,
      IndexedDurabilityCohortRing pending, IndexedTable table) {
    this.batch = batch;
    this.metrics = metrics;
    this.pending = pending;
    this.table = table;
  }

  StatusCode completeForce() {
    long coveredEnd = table.submittedForceEnd();
    long forceNanos = table.submittedForceNanos();
    StatusCode status = table.completeSubmittedForce();
    metrics.recordStage(
        IndexedCommitPath.SHARED_GROUP, IndexedCommitStage.GROUP_FORCE, forceNanos);
    if (!status.isOk()) {
      table.fenceCommitWriter();
      failPending(status);
      return status;
    }
    status = table.releaseSubmittedForce();
    if (!status.isOk()) {
      table.fenceCommitWriter();
      failPending(status);
      return status;
    }
    while (!pending.empty() && pending.requiredWalEnd() <= coveredEnd) {
      int count = pending.headMemberCount();
      status = pending.loadHead(batch);
      if (status.isOk()) {
        status = table.releaseDurabilityChain(pending.token(), pending.frameHead());
      }
      if (status.isOk()) status = batch.completePublished(count);
      if (!status.isOk()) {
        table.fenceCommitWriter();
        batch.failPublished(count, status);
      }
      batch.complete(count);
      StatusCode removed = pending.removeHead();
      if (!status.isOk() || !removed.isOk()) {
        failPending(status.isOk() ? removed : status);
        return status.isOk() ? removed : status;
      }
    }
    table.pendingDurabilitySequence(pending.firstCommitSequence());
    return StatusCode.OK;
  }

  void failPending(StatusCode failure) {
    while (!pending.empty()) {
      int count = pending.headMemberCount();
      StatusCode status = pending.loadHead(batch);
      StatusCode release = table.releaseDurabilityChain(pending.token(), pending.frameHead());
      if (status.isOk()) status = batch.failPublished(count, failure);
      if (!release.isOk() || !status.isOk()) table.fenceCommitWriter();
      batch.complete(count);
      if (!pending.removeHead().isOk()) break;
    }
    table.pendingDurabilitySequence(0);
  }
}
