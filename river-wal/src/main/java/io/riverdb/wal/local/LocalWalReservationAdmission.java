package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Admits one bounded payload reservation into the next WAL append slot. */
final class LocalWalReservationAdmission {
  private LocalWalReservationAdmission() {
  }

  static StatusCode reserve(
      LocalWal wal,
      int payloadBytes,
      LocalWalReservation reservation) {
    if (reservation == null
        || payloadBytes < 0
        || payloadBytes > WalRecordCodec.MAX_PAYLOAD_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode admission = wal.admissionStatus();
    if (!admission.isOk()) {
      return admission;
    }
    if (wal.hasOpenLogicalStream()) {
      return StatusCode.CONFLICT;
    }
    int recordBytes = WalRecordCodec.encodedBytes(payloadBytes);
    if (wal.hasActiveReservation()
        || wal.hasRetainedForceTarget() && !wal.forceState().concurrentAppendPermitted()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (wal.appendState().nextJournalSequence() <= 0
        || wal.appendState().tailEnd() > Long.MAX_VALUE - recordBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    ByteBuffer appendRecord = wal.appendState().appendRecord();
    ByteBuffer appendPayload = wal.appendState().appendPayload();
    appendRecord.clear();
    appendPayload.clear();
    appendPayload.limit(payloadBytes);
    long token = wal.claimNextReservationToken();
    long endOffset = wal.appendState().tailEnd() + recordBytes;
    StatusCode status = reservation.claim(
        wal, token, appendPayload, payloadBytes, wal.appendState().tailEnd(), endOffset);
    if (status.isOk()) {
      wal.activateReservation(token);
    }
    return status;
  }
}
