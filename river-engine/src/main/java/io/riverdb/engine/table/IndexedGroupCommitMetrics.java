package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Single-owner, bounded counters for indexed commit paths. */
final class IndexedGroupCommitMetrics {
  private final IndexedGroupCommitTelemetry values = new IndexedGroupCommitTelemetry();
  private final IndexedGroupCommitTelemetry capture = new IndexedGroupCommitTelemetry();
  private boolean capturing;

  synchronized void copyTo(IndexedGroupCommitTelemetry target) {
    target.copyFrom(values);
  }

  synchronized StatusCode beginCapture() {
    if (capturing) return StatusCode.CONFLICT;
    capture.reset();
    capturing = true;
    return StatusCode.OK;
  }

  synchronized StatusCode endCapture(IndexedGroupCommitTelemetry target) {
    if (!capturing || target == null) return StatusCode.CONFLICT;
    capturing = false;
    target.copyFrom(capture);
    return StatusCode.OK;
  }

  synchronized StatusCode cancelCapture() {
    if (!capturing) return StatusCode.CONFLICT;
    capturing = false;
    capture.reset();
    return StatusCode.OK;
  }

  synchronized void recordReadOnlyCommit() {
    values.recordReadOnlyCommit();
    if (capturing) capture.recordReadOnlyCommit();
  }

  synchronized void recordFailedBefore(StatusCode status) {
    values.recordFailedBefore(status);
    if (capturing) capture.recordFailedBefore(status);
  }

  synchronized void recordWriteSubmission(int mask, boolean coordinatorAdmission) {
    values.recordWriteSubmission(mask, coordinatorAdmission);
    if (capturing) capture.recordWriteSubmission(mask, coordinatorAdmission);
  }

  synchronized void recordAttemptedGroup(int count) {
    values.recordAttemptedGroup(count);
    if (capturing) capture.recordAttemptedGroup(count);
  }

  synchronized void recordSuccessfulGroup(int count) {
    values.recordSuccessfulGroup(count);
    if (capturing) capture.recordSuccessfulGroup(count);
  }

  synchronized void recordDirectCommit(IndexedDirectCommitReason reason) {
    values.recordDirectCommit(reason);
    if (capturing) capture.recordDirectCommit(reason);
  }

  synchronized void recordGroupFailure(
      IndexedGroupFailureStage stage, StatusCode status, int transactionCount) {
    values.recordGroupFailure(stage, status, transactionCount);
    if (capturing) capture.recordGroupFailure(stage, status, transactionCount);
  }

  synchronized void recordQueueEnqueue(int depth) {
    values.queue().recordEnqueue(depth);
    if (capturing) capture.queue().recordEnqueue(depth);
  }

  synchronized void recordWriterSelection(
      int count,
      int depth,
      int groupableDepth,
      boolean groupableSelection,
      boolean capacityConstrained) {
    values.queue().recordWriterSelection(
        count, depth, groupableDepth, groupableSelection, capacityConstrained);
    if (capturing) {
      capture.queue().recordWriterSelection(
          count, depth, groupableDepth, groupableSelection, capacityConstrained);
    }
  }

  synchronized void recordQueueNonempty(long elapsedNanos) {
    values.queue().recordNonempty(elapsedNanos);
    if (capturing) capture.queue().recordNonempty(elapsedNanos);
  }

  synchronized void recordWriterBusy(long elapsedNanos) {
    values.queue().recordWriterBusy(elapsedNanos);
    if (capturing) capture.queue().recordWriterBusy(elapsedNanos);
  }

  synchronized void recordCoalescingWait(long elapsedNanos) {
    values.queue().recordCoalescingWait(elapsedNanos);
    if (capturing) capture.queue().recordCoalescingWait(elapsedNanos);
  }

  synchronized void recordStage(
      IndexedCommitPath path, IndexedCommitStage stage, long elapsedNanos) {
    values.recordStage(path, stage, elapsedNanos);
    if (capturing) capture.recordStage(path, stage, elapsedNanos);
  }

  synchronized void recordStageFailure(
      IndexedCommitPath path, IndexedCommitStage stage, StatusCode status) {
    values.recordStageFailure(path, stage, status);
    if (capturing) capture.recordStageFailure(path, stage, status);
  }

}
