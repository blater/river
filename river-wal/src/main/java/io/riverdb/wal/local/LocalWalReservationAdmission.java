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
    int recordBytes = WalRecordCodec.encodedBytes(payloadBytes);
    if (wal.hasActiveReservation() || wal.hasForcedBatch()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (wal.pendingRecordCountValue() >= LocalWal.MAX_PENDING_RECORDS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (wal.nextJournalSequenceValue() <= 0
        || wal.tailEnd() > Long.MAX_VALUE - recordBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    ByteBuffer appendRecord = wal.appendRecordBuffer();
    ByteBuffer appendPayload = wal.appendPayloadBuffer();
    appendRecord.clear();
    appendPayload.clear();
    appendPayload.limit(payloadBytes);
    long token = wal.claimNextReservationToken();
    long endOffset = wal.tailEnd() + recordBytes;
    StatusCode status = reservation.claim(
        wal, token, appendPayload, payloadBytes, wal.tailEnd(), endOffset);
    if (status.isOk()) {
      wal.activateReservation(token);
    }
    return status;
  }
}
