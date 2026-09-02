package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** All-or-nothing capacity admission for one contiguous logical WAL record group. */
final class LocalWalGroupAdmission {
  private LocalWalGroupAdmission() { }

  static StatusCode reserve(
      LocalWal wal, int[] payloadSizes, int count, LocalWalGroupReservation reservation) {
    return reserve(wal, null, payloadSizes, count, reservation);
  }

  static StatusCode reserve(
      LocalWal wal,
      LocalWalLogicalStream stream,
      int[] payloadSizes,
      int count,
      LocalWalGroupReservation reservation) {
    if (reservation == null || payloadSizes == null || count < 1
        || count > LocalWal.MAX_PENDING_RECORDS || count > payloadSizes.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (stream == null ? wal.hasOpenLogicalStream() : !wal.ownsLogicalStream(stream)) {
      return StatusCode.CONFLICT;
    }
    if (wal.hasActiveReservation() || wal.hasForcedBatch()
        || wal.pendingRecordCountValue() > LocalWal.MAX_PENDING_RECORDS - count) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long sequence = wal.nextJournalSequenceValue();
    if (sequence <= 0 || sequence > Long.MAX_VALUE - count + 1) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long end = wal.tailEnd();
    for (int index = 0; index < count; index++) {
      int bytes = WalRecordCodec.encodedBytes(payloadSizes[index]);
      if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (end > Long.MAX_VALUE - bytes) return StatusCode.RESOURCE_EXHAUSTED;
      end += bytes;
    }
    long token = wal.claimNextReservationToken();
    status = reservation.claim(wal, token, wal.logicalStreamToken(stream), count);
    if (!status.isOk()) return status;
    int first = wal.pendingRecordCountValue();
    for (int index = 0; index < count; index++) {
      ByteBuffer record = wal.appendRecordBufferAt(first + index);
      ByteBuffer payload = wal.appendPayloadBufferAt(first + index);
      record.clear();
      payload.clear();
      payload.limit(payloadSizes[index]);
      reservation.set(index, payload, payloadSizes[index]);
    }
    wal.activateReservation(token);
    return StatusCode.OK;
  }
}
