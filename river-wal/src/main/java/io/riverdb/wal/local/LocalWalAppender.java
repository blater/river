package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Encodes and appends one reserved WAL record without forcing it durable. */
final class LocalWalAppender {
  private LocalWalAppender() {
  }

  static StatusCode append(
      LocalWal wal,
      LocalWalReservation reservation,
      long transactionId,
      long commitSequence,
      int decisionCode,
      int formatId,
      int formatVersion,
      LocalWalAppendResult result) {
    if (reservation == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) {
      return status;
    }
    if (!wal.ownsReservation(reservation)
        || reservation.writablePayload().position() != reservation.payloadBytes()) {
      return wal.ownsReservation(reservation)
          ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.CONFLICT;
    }
    if (!wal.validDecisionForAppend(transactionId, commitSequence, decisionCode)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int recordBytes = WalRecordCodec.encodedBytes(reservation.payloadBytes());
    ByteBuffer appendRecord = wal.appendRecordBuffer();
    status = WalRecordCodec.encodeReserved(
        wal.nextJournalSequenceValue(),
        transactionId,
        commitSequence,
        decisionCode,
        formatId,
        formatVersion,
        reservation.payloadBytes(),
        appendRecord,
        wal.appendChecksum());
    if (status.isOk()) {
      status = wal.writeAppendRecord(wal.tailEnd(), appendRecord, recordBytes);
    }
    if (!status.isOk()) {
      wal.abortAppend(reservation);
      return status;
    }
    wal.acceptAppend(
        reservation,
        result,
        transactionId,
        commitSequence,
        decisionCode,
        recordBytes);
    return StatusCode.OK;
  }
}
