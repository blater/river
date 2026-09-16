package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Owns authentication and lifecycle for the one non-interleavable logical stream. */
final class LocalWalStreamState {
  private final LocalWal owner;
  private long token;
  private boolean appended;
  private boolean finalAppended;

  LocalWalStreamState(LocalWal owner) { this.owner = owner; }

  boolean open() { return token != 0; }
  boolean owns(LocalWalLogicalStream stream) {
    return stream != null && stream.isOwnedBy(owner, token);
  }
  long token(LocalWalLogicalStream stream) { return stream == null ? 0 : token; }
  boolean appended() { return appended; }

  StatusCode begin(
      long transactionId, int formatId, int formatVersion, LocalWalLogicalStream stream) {
    if (stream == null || transactionId <= 0 || formatId <= 0 || formatVersion <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = owner.admissionStatus();
    if (!status.isOk()) return status;
    if (open() || owner.hasActiveReservation() || owner.hasPendingRecords()
        || owner.hasRetainedForceTarget()) return StatusCode.CONFLICT;
    long next = owner.claimNextReservationToken();
    status = stream.claim(owner, next, transactionId, formatId, formatVersion);
    if (!status.isOk()) return status;
    token = next;
    owner.recoveryTailOpen = false;
    appended = false;
    finalAppended = false;
    if (owner.hasDurableQuorum()) {
      status = owner.durableQuorum().beginLogicalStream(transactionId, formatId, formatVersion);
      if (!status.isOk()) {
        complete(stream);
        if (status == StatusCode.FENCED) owner.markFailed();
      }
    }
    return status;
  }

  StatusCode release(LocalWalLogicalStream stream, LocalWalForceTarget target, long forceToken) {
    if (!owns(stream)) return StatusCode.CONFLICT;
    StatusCode status = owner.forceState().release(target, forceToken);
    if (status.isOk() && finalAppended) complete(stream);
    return status;
  }

  StatusCode cancel(LocalWalLogicalStream stream) {
    if (!owns(stream)) return StatusCode.CONFLICT;
    if (appended || owner.hasPendingRecords() || owner.hasRetainedForceTarget()
        || owner.hasActiveReservation()) return StatusCode.CONFLICT;
    StatusCode status = owner.cancelDurableLogicalStream();
    if (status.isOk()) complete(stream);
    return status;
  }

  StatusCode fence(LocalWalLogicalStream stream) {
    if (!owns(stream)) return StatusCode.CONFLICT;
    owner.failed = true;
    owner.clearActiveReservation();
    owner.fenceDurableLogicalStream();
    complete(stream);
    return StatusCode.OK;
  }

  void acceptBatch(boolean isFinal) {
    appended = true;
    finalAppended = isFinal;
  }

  void complete(LocalWalLogicalStream stream) {
    token = 0;
    appended = false;
    finalAppended = false;
    stream.complete();
  }
}
