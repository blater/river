package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Captures one pending append prefix before I/O and coordinates its durable quorum. */
final class LocalWalForceCoordinator {
  private LocalWalForceCoordinator() {
  }

  static StatusCode force(
      LocalWal wal, LocalWalForceTarget target, LocalWalForceCause cause) {
    if (target == null || cause == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (wal.hasActiveReservation() || !wal.hasPendingRecords() || wal.hasRetainedForceTarget()) {
      return StatusCode.CONFLICT;
    }
    status = wal.captureForceTarget(target);
    if (!status.isOk()) return status;
    try {
      status = wal.forceAppendFile(target, cause);
      return completeCaptured(wal, target, cause, status);
    } finally {
      // Unexpected provider failures unwind to the owner, but cannot strand its file lease.
      if (!target.durabilityComplete()) wal.markFailed();
      wal.finishForce();
    }
  }

  static StatusCode enable(LocalWal wal, Thread completionOwner) {
    if (wal == null || completionOwner == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = wal.admissionStatus();
    return status.isOk() ? wal.installForceWorker(completionOwner) : status;
  }

  static StatusCode seal(LocalWal wal) {
    if (wal == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (wal.hasActiveReservation() || wal.pendingRecordCountValue() <= 0) {
      return StatusCode.CONFLICT;
    }
    return wal.appendPendingFooter();
  }

  static StatusCode submit(
      LocalWal wal, LocalWalForceTarget target, LocalWalForceCause cause) {
    if (wal == null || target == null || cause == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (!wal.forceWorkerEnabled() || wal.hasRetainedForceTarget() || !wal.hasSealedRecords()
        || wal.hasActiveReservation() || wal.pendingRecordCountValue() != 0
        || wal.hasOpenLogicalStream()) {
      return StatusCode.CONFLICT;
    }
    status = wal.captureSealedForceTarget(target);
    if (!status.isOk()) return status;
    wal.beginAsyncForce(cause);
    status = wal.submitForceCommand(target, cause);
    if (!status.isOk()) {
      wal.takeAsyncForceCause();
      wal.restoreCapturedSuffix(target);
    }
    return status;
  }

  static boolean completed(LocalWal wal, LocalWalForceTarget target) {
    return wal != null && target != null && wal.forceResultReady(target);
  }

  static StatusCode complete(LocalWal wal, LocalWalForceTarget target, long token) {
    if (wal == null || target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!wal.ownsForceTarget(target, token)) return StatusCode.CONFLICT;
    StatusCode status = wal.consumeForceResult(target);
    if (!status.isOk()) return status;
    LocalWalForceCause cause = wal.takeAsyncForceCause();
    if (cause == null) cause = LocalWalForceCause.OTHER;
    wal.recordAsyncForce(target, cause);
    try {
      return completeCaptured(wal, target, cause, target.forceStatus());
    } finally {
      if (!target.durabilityComplete()) wal.markFailed();
      wal.finishForce();
    }
  }

  private static StatusCode completeCaptured(
      LocalWal wal,
      LocalWalForceTarget target,
      LocalWalForceCause cause,
      StatusCode localForceStatus) {
    StatusCode status = localForceStatus;
    if (status == null) status = StatusCode.INVARIANT_BROKEN;
    if (status.isOk()) status = wal.validateCapturedTarget(target);
    if (status.isOk()) {
      // Local durable truth survives a later quorum failure or reentrant fence.
      wal.markForced(target);
      status = wal.admissionStatus();
      if (status.isOk() && wal.hasDurableQuorum()) {
        status = wal.replicateForcedBatch(target, cause);
      }
      if (status.isOk()) status = wal.admissionStatus();
    }
    if (status.isOk()) wal.completeForceDurability(target);
    return status;
  }
}
