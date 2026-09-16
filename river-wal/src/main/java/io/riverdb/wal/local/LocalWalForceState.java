package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.ForceMode;

/** Owns force targets, asynchronous worker state, and bounded force metrics. */
final class LocalWalForceState {
  private final LocalWal owner;
  private final LocalWalAppendState append;
  private final LocalWalForceMetrics metrics = new LocalWalForceMetrics();
  private LocalWalForceTarget activeTarget;
  private LocalWalForceWorker worker;
  private LocalWalForceCause activeCause;
  private boolean inProgress;

  LocalWalForceState(LocalWal owner, LocalWalAppendState append) {
    this.owner = owner;
    this.append = append;
  }

  LocalWalForceMetrics metrics() { return metrics; }
  void mergeMetrics(LocalWalForceState other) { metrics.merge(other.metrics); }
  boolean hasTarget() { return activeTarget != null; }
  boolean inProgress() { return inProgress; }
  boolean hasWorker() { return worker != null; }
  boolean concurrentAppendPermitted() { return worker != null && inProgress; }
  boolean owns(LocalWalForceTarget target, long token) {
    return target != null && activeTarget == target && target.ownedBy(owner, token);
  }

  StatusCode openCursor(LocalWalForceTarget target, long token, LocalWalForcedCursor cursor) {
    if (target == null || cursor == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!owns(target, token) || !target.locallyForced()) return StatusCode.CONFLICT;
    return cursor.open(owner, target, token);
  }

  StatusCode release(LocalWalForceTarget target, long token) {
    StatusCode status = owner.admissionStatus();
    if (!status.isOk()) return status;
    if (inProgress || !owns(target, token) || !target.durabilityComplete()) {
      return StatusCode.CONFLICT;
    }
    target.release();
    releaseTarget();
    return StatusCode.OK;
  }

  StatusCode forceFile(LocalWalForceCause cause, long coveredBytes) {
    long started = System.nanoTime();
    StatusCode status = append.file().force(ForceMode.CONTENT_AND_METADATA);
    metrics.record(cause, coveredBytes, System.nanoTime() - started, status);
    return status;
  }

  StatusCode forceRange(LocalWalForceCause cause, long startOffset, long endOffset) {
    long started = System.nanoTime();
    StatusCode status = append.file().force(
        startOffset, endOffset, ForceMode.CONTENT_AND_METADATA);
    metrics.record(cause, endOffset - startOffset, System.nanoTime() - started, status);
    return status;
  }

  StatusCode forceTarget(LocalWalForceTarget target, LocalWalForceCause cause) {
    long started = System.nanoTime();
    StatusCode status = target.file().force(
        target.startOffset(), target.endOffset(), ForceMode.CONTENT_AND_METADATA);
    metrics.record(
        cause, target.endOffset() - target.startOffset(), System.nanoTime() - started, status);
    return status;
  }

  StatusCode capture(LocalWalForceTarget target) {
    if (target.retained() || activeTarget != null || !append.hasSealedRecords()) {
      return StatusCode.CONFLICT;
    }
    if (owner.nextForceToken == 0) return StatusCode.RESOURCE_EXHAUSTED;
    long token = owner.nextForceToken;
    owner.nextForceToken = token == Long.MAX_VALUE ? 0 : token + 1;
    append.captureSealedValues(target, token);
    activeTarget = target;
    inProgress = true;
    append.clearSealed();
    return StatusCode.OK;
  }

  void restore(LocalWalForceTarget target) {
    append.restoreCapturedSuffix(target);
    target.release();
    activeTarget = null;
    inProgress = false;
  }

  void releaseTarget() {
    if (activeTarget != null) activeTarget.release();
    activeTarget = null;
    inProgress = false;
  }

  StatusCode validate(LocalWalForceTarget target) {
    return target.file() == append.file()
            && target.startOffset() == append.durableEnd()
            && target.endOffset() > target.startOffset()
            && target.endOffset() <= append.tailEnd()
            && target.previousDigest() == append.durableDigest()
        ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  void markForced(LocalWalForceTarget target) {
    append.setDurableFrontier(target);
    target.completeLocalForce();
  }

  void finish() { inProgress = false; }

  StatusCode installWorker(Thread completionOwner) {
    if (completionOwner == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (worker != null) return StatusCode.CONFLICT;
    worker = new LocalWalForceWorker(completionOwner);
    return StatusCode.OK;
  }

  StatusCode submit(LocalWalForceTarget target, LocalWalForceCause cause) {
    return worker == null ? StatusCode.CONFLICT : worker.submit(target, cause);
  }

  boolean resultReady(LocalWalForceTarget target) {
    return worker != null && worker.resultReady(target);
  }

  StatusCode consume(LocalWalForceTarget target) {
    return worker == null ? StatusCode.CONFLICT : worker.consume(target);
  }

  void beginAsync(LocalWalForceCause cause) { activeCause = cause; }

  LocalWalForceCause takeAsyncCause() {
    LocalWalForceCause cause = activeCause;
    activeCause = null;
    return cause;
  }

  void recordAsync(LocalWalForceTarget target, LocalWalForceCause cause) {
    metrics.record(
        cause, target.endOffset() - target.startOffset(),
        target.forceElapsedNanos(), target.forceStatus());
  }

  void completeDurability(LocalWalForceTarget target) { target.completeDurability(); }

  StatusCode closeWorker() {
    if (worker == null) return StatusCode.OK;
    StatusCode status = worker.close();
    if (status.isOk() || status == StatusCode.CLOSED) {
      worker = null;
      inProgress = false;
    }
    return status;
  }
}
