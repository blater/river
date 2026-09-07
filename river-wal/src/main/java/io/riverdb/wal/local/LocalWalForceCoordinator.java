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
      if (status.isOk()) {
        // Local durable truth survives a later quorum failure or reentrant fence.
        wal.markForced(target);
        status = wal.admissionStatus();
        if (status.isOk() && wal.hasDurableQuorum()) {
          status = wal.replicateForcedBatch(target, cause);
        }
        if (status.isOk()) status = wal.admissionStatus();
      }
      if (status.isOk()) target.completeDurability();
      return status;
    } finally {
      // Unexpected provider failures unwind to the owner, but cannot strand its file lease.
      if (!target.durabilityComplete()) wal.markFailed();
      wal.finishForce();
    }
  }
}
