package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Streams an admitted logical record batch through one provider-owned physical buffer. */
final class LocalWalRecordBatchAppender {
  private LocalWalRecordBatchAppender() {}

  static StatusCode appendFinal(
      LocalWal wal,
      LocalWalRecordBatch batch,
      long transactionId,
      long commitSequence,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    return append(
        wal, null, batch, transactionId, commitSequence,
        formatId, formatVersion, true, result);
  }

  static StatusCode appendFinal(
      LocalWal wal,
      LocalWalLogicalStream stream,
      LocalWalRecordBatch batch,
      long commitSequence,
      LocalWalGroupAppendResult result) {
    if (stream == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!wal.ownsLogicalStream(stream)) return StatusCode.CONFLICT;
    return append(
        wal, stream, batch, stream.transactionId(), commitSequence,
        stream.formatId(), stream.formatVersion(), true, result);
  }

  static StatusCode appendContinuation(
      LocalWal wal,
      LocalWalRecordBatch batch,
      long transactionId,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    return append(
        wal, null, batch, transactionId, 0,
        formatId, formatVersion, false, result);
  }

  static StatusCode appendContinuation(
      LocalWal wal,
      LocalWalLogicalStream stream,
      LocalWalRecordBatch batch,
      LocalWalGroupAppendResult result) {
    if (stream == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!wal.ownsLogicalStream(stream)) return StatusCode.CONFLICT;
    return append(
        wal, stream, batch, stream.transactionId(), 0,
        stream.formatId(), stream.formatVersion(), false, result);
  }

  private static StatusCode append(
      LocalWal wal,
      LocalWalLogicalStream stream,
      LocalWalRecordBatch batch,
      long transactionId,
      long commitSequence,
      int formatId,
      int formatVersion,
      boolean finalBatch,
      LocalWalGroupAppendResult result) {
    if (wal == null || batch == null || result == null
        || transactionId <= 0 || formatId <= 0 || formatVersion <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (stream == null ? wal.hasOpenLogicalStream() : !wal.ownsLogicalStream(stream)) {
      return StatusCode.CONFLICT;
    }
    if (wal.hasActiveReservation() || wal.hasForcedBatch()) return StatusCode.CONFLICT;
    if (finalBatch && !wal.validDecisionForAppend(transactionId, commitSequence, 1)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    LocalWalBatchAdmissionResult admission = wal.batchAdmissionResult();
    status = LocalWalBatchAdmission.admit(wal, batch, admission);
    if (!status.isOk()) return status;
    int records = admission.recordCount();
    long end = admission.endOffset();

    long offset = wal.tailEnd();
    long firstSequence = wal.nextJournalSequenceValue();
    for (int record = 0; record < records; record++) {
      int payloadBytes = batch.payloadBytes(record);
      ByteBuffer payload = wal.prepareAppendPayload(payloadBytes);
      status = batch.encodePayload(record, payload);
      if (status.isOk() && payload.position() != payloadBytes) {
        status = StatusCode.INVARIANT_BROKEN;
      }
      if (!status.isOk()) return fail(wal, offset != wal.tailEnd(), status);
      boolean decision = finalBatch && record + 1 == records;
      status = WalRecordCodec.encodeReserved(
          firstSequence + record,
          transactionId,
          decision ? commitSequence : 0,
          decision ? 1 : 0,
          formatId,
          formatVersion,
          payloadBytes,
          wal.appendRecordBuffer(),
          wal.appendChecksum());
      if (!status.isOk()) return fail(wal, offset != wal.tailEnd(), status);
      int recordBytes = WalRecordCodec.encodedBytes(payloadBytes);
      result.markStorageMayHaveChanged();
      status = wal.writeAppendRecord(offset, wal.appendRecordBuffer(), recordBytes);
      if (!status.isOk()) return fail(wal, true, status);
      offset += recordBytes;
    }
    wal.acceptRecordBatchAppend(
        result, transactionId, finalBatch ? commitSequence : 0, end, records);
    if (stream != null) wal.acceptLogicalStreamBatch(finalBatch);
    return StatusCode.OK;
  }

  private static StatusCode fail(LocalWal wal, boolean bytesWritten, StatusCode status) {
    if (bytesWritten) wal.abortRecordBatchAppend();
    return status;
  }
}
