package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Validates, encodes, then exclusively appends one complete logical record group. */
final class LocalWalGroupAppender {
  private LocalWalGroupAppender() { }

  static StatusCode appendFinal(
      LocalWal wal,
      LocalWalGroupReservation reservation,
      long transactionId,
      long commitSequence,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    return append(wal, null, reservation, transactionId, commitSequence,
        formatId, formatVersion, true, result);
  }

  static StatusCode appendFinal(
      LocalWal wal,
      LocalWalLogicalStream stream,
      LocalWalGroupReservation reservation,
      long commitSequence,
      LocalWalGroupAppendResult result) {
    if (stream == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return append(wal, stream, reservation, stream.transactionId(), commitSequence,
        stream.formatId(), stream.formatVersion(), true, result);
  }

  static StatusCode appendContinuation(
      LocalWal wal,
      LocalWalGroupReservation reservation,
      long transactionId,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    return append(wal, null, reservation, transactionId, 0,
        formatId, formatVersion, false, result);
  }

  static StatusCode appendContinuation(
      LocalWal wal,
      LocalWalLogicalStream stream,
      LocalWalGroupReservation reservation,
      LocalWalGroupAppendResult result) {
    if (stream == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    return append(wal, stream, reservation, stream.transactionId(), 0,
        stream.formatId(), stream.formatVersion(), false, result);
  }

  private static StatusCode append(
      LocalWal wal,
      LocalWalLogicalStream stream,
      LocalWalGroupReservation reservation,
      long transactionId,
      long commitSequence,
      int formatId,
      int formatVersion,
      boolean finalGroup,
      LocalWalGroupAppendResult result) {
    if (reservation == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (stream == null ? wal.hasOpenLogicalStream() : !wal.ownsLogicalStream(stream)) {
      return StatusCode.CONFLICT;
    }
    if (!wal.ownsGroupReservation(reservation)) return StatusCode.CONFLICT;
    if (!reservation.belongsToStream(wal.logicalStreamToken(stream))) {
      return StatusCode.CONFLICT;
    }
    int count = reservation.recordCount();
    for (int index = 0; index < count; index++) {
      if (reservation.writablePayload(index).position() != reservation.payloadBytes(index)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    if (transactionId <= 0
        || (finalGroup && !wal.validDecisionForAppend(transactionId, commitSequence, 1))) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int first = wal.pendingRecordCountValue();
    long sequence = wal.nextJournalSequenceValue();
    for (int index = 0; index < count; index++) {
      ByteBuffer record = wal.appendRecordBufferAt(first + index);
      boolean decision = finalGroup && index + 1 == count;
      status = WalRecordCodec.encodeReserved(
          sequence + index, transactionId, decision ? commitSequence : 0,
          decision ? 1 : 0, formatId, formatVersion,
          reservation.payloadBytes(index), record, wal.appendChecksum());
      if (!status.isOk()) return status;
    }
    long offset = wal.tailEnd();
    for (int index = 0; index < count; index++) {
      int bytes = WalRecordCodec.encodedBytes(reservation.payloadBytes(index));
      status = wal.writeAppendRecord(offset, wal.appendRecordBufferAt(first + index), bytes);
      if (!status.isOk()) {
        wal.abortGroupAppend(reservation);
        return status;
      }
      offset += bytes;
    }
    wal.acceptGroupAppend(
        reservation, result, transactionId, finalGroup ? commitSequence : 0);
    if (stream != null) wal.acceptLogicalStreamBatch(finalGroup);
    return StatusCode.OK;
  }

}
