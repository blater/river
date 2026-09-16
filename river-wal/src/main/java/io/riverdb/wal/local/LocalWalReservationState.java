package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Owns completion and fencing decisions for an admitted reservation batch. */
final class LocalWalReservationState {
  private final LocalWal owner;

  LocalWalReservationState(LocalWal owner) { this.owner = owner; }

  StatusCode cancel(LocalWalReservation reservation) {
    if (reservation == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = owner.admissionStatus();
    if (!status.isOk()) return status;
    if (!owner.ownsReservation(reservation)) return StatusCode.CONFLICT;
    owner.clearActiveReservation();
    reservation.complete();
    return StatusCode.OK;
  }

  StatusCode fencePendingBatch() {
    if ((!owner.hasPendingRecords() && !owner.hasRetainedForceTarget())
        || owner.hasActiveReservation()) return StatusCode.CONFLICT;
    owner.markFailed();
    return StatusCode.OK;
  }
}
