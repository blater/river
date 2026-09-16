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
      status = wal.forceState().forceTarget(target, cause);
      return completeCaptured(wal, target, cause, status);
    } finally {
      // Unexpected provider failures unwind to the owner, but cannot strand its file lease.
      if (!target.durabilityComplete()) wal.markFailed();
      wal.forceState().finish();
    }
  }

  static StatusCode enable(LocalWal wal, Thread completionOwner) {
    if (wal == null || completionOwner == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = wal.admissionStatus();
    return status.isOk() ? wal.forceState().installWorker(completionOwner) : status;
  }

  static StatusCode seal(LocalWal wal) {
    if (wal == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (wal.hasActiveReservation() || wal.appendState().pendingRecordCount() <= 0) {
      return StatusCode.CONFLICT;
    }
    return wal.appendState().appendPendingFooter();
  }

  static StatusCode submit(
      LocalWal wal, LocalWalForceTarget target, LocalWalForceCause cause) {
    if (wal == null || target == null || cause == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (!wal.forceState().hasWorker() || wal.hasRetainedForceTarget() || !wal.appendState().hasSealedRecords()
        || wal.hasActiveReservation() || wal.appendState().pendingRecordCount() != 0
        || wal.hasOpenLogicalStream()) {
      return StatusCode.CONFLICT;
    }
    status = wal.forceState().capture(target);
    if (!status.isOk()) return status;
    wal.forceState().beginAsync(cause);
    status = wal.forceState().submit(target, cause);
    if (!status.isOk()) {
      wal.forceState().takeAsyncCause();
      wal.forceState().restore(target);
    }
    return status;
  }

  static boolean completed(LocalWal wal, LocalWalForceTarget target) {
    return wal != null && target != null && wal.forceState().resultReady(target);
  }

  static StatusCode complete(LocalWal wal, LocalWalForceTarget target, long token) {
    if (wal == null || target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!wal.forceState().owns(target, token)) return StatusCode.CONFLICT;
    StatusCode status = wal.forceState().consume(target);
    if (!status.isOk()) return status;
    LocalWalForceCause cause = wal.forceState().takeAsyncCause();
    if (cause == null) cause = LocalWalForceCause.OTHER;
    wal.forceState().recordAsync(target, cause);
    try {
      return completeCaptured(wal, target, cause, target.forceStatus());
    } finally {
      if (!target.durabilityComplete()) wal.markFailed();
      wal.forceState().finish();
    }
  }

  private static StatusCode completeCaptured(
      LocalWal wal,
      LocalWalForceTarget target,
      LocalWalForceCause cause,
      StatusCode localForceStatus) {
    StatusCode status = localForceStatus;
    if (status == null) status = StatusCode.INVARIANT_BROKEN;
    if (status.isOk()) status = wal.forceState().validate(target);
    if (status.isOk()) {
      // Local durable truth survives a later quorum failure or reentrant fence.
      wal.forceState().markForced(target);
      status = wal.admissionStatus();
      if (status.isOk() && wal.hasDurableQuorum()) {
        status = wal.replicateForcedBatch(target, cause);
      }
      if (status.isOk()) status = wal.admissionStatus();
    }
    if (status.isOk()) wal.forceState().completeDurability(target);
    return status;
  }
}
