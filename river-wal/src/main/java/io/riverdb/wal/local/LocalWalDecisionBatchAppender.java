package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Streams independently decided transaction records into one unforced WAL cohort. */
final class LocalWalDecisionBatchAppender {
  private LocalWalDecisionBatchAppender() {}

  static StatusCode append(
      LocalWal wal,
      LocalWalDecisionBatch batch,
      int formatId,
      int formatVersion,
      LocalWalGroupAppendResult result) {
    if (wal == null || batch == null || result == null
        || formatId <= 0 || formatVersion <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = validate(wal, batch);
    if (!status.isOk()) return status;
    LocalWalBatchAdmissionResult admission = wal.batchAdmissionResult();
    status = LocalWalBatchAdmission.admit(wal, batch, admission);
    if (!status.isOk()) return status;
    long end = admission.endOffset();

    long offset = wal.tailEnd();
    long firstSequence = wal.nextJournalSequenceValue();
    int transaction = 0;
    for (int record = 0; record < batch.recordCount(); record++) {
      int payloadBytes = batch.payloadBytes(record);
      ByteBuffer payload = wal.prepareAppendPayload(payloadBytes);
      status = batch.encodePayload(record, payload);
      if (status.isOk() && payload.position() != payloadBytes) {
        status = StatusCode.INVARIANT_BROKEN;
      }
      if (!status.isOk()) return fail(wal, offset != wal.tailEnd(), status);
      boolean decision = record + 1 == batch.transactionEndRecord(transaction);
      status = WalRecordCodec.encodeReserved(
          firstSequence + record,
          batch.transactionId(transaction),
          decision ? batch.commitSequence(transaction) : 0,
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
      if (decision) transaction++;
    }
    wal.acceptDecisionBatchAppend(result, batch, end);
    return StatusCode.OK;
  }

  private static StatusCode validate(LocalWal wal, LocalWalDecisionBatch batch) {
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) return status;
    if (wal.hasOpenLogicalStream() || wal.hasActiveReservation() || wal.hasForcedBatch()) {
      return StatusCode.CONFLICT;
    }
    int records = batch.recordCount();
    int transactions = batch.transactionCount();
    if (records <= 0 || transactions <= 0 || transactions > records) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int previousEnd = 0;
    long sequence = wal.nextCommitSequence();
    if (sequence <= 0 || sequence > Long.MAX_VALUE - transactions + 1) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int transaction = 0; transaction < transactions; transaction++) {
      int end = batch.transactionEndRecord(transaction);
      if (batch.transactionId(transaction) <= 0
          || end <= previousEnd
          || end > records
          || batch.commitSequence(transaction) != sequence) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      previousEnd = end;
      sequence = sequence == Long.MAX_VALUE ? 0 : sequence + 1;
    }
    return previousEnd == records ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static StatusCode fail(LocalWal wal, boolean bytesWritten, StatusCode status) {
    if (bytesWritten) wal.abortRecordBatchAppend();
    return status;
  }
}
