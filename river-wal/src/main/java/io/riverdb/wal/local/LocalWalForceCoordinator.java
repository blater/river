package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Forces one pending append batch and coordinates its durable quorum. */
final class LocalWalForceCoordinator {
  private LocalWalForceCoordinator() {
  }

  static StatusCode force(LocalWal wal, LocalWalForceResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) {
      return status;
    }
    if (wal.hasActiveReservation() || !wal.hasPendingRecords() || wal.hasForcedBatch()) {
      return StatusCode.CONFLICT;
    }
    status = wal.forceAppendFile();
    if (!status.isOk()) {
      wal.markFailed();
      return status;
    }
    wal.markForced(result);
    if (!wal.hasDurableQuorum()) {
      return StatusCode.OK;
    }
    status = wal.replicateForcedBatch();
    if (!status.isOk()) {
      wal.markFailed();
      return status;
    }
    return StatusCode.OK;
  }
}
