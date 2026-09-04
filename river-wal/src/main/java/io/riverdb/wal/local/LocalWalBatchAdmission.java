package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalRecordCodec;

/** Owns complete-range address admission for streamed WAL record batches. */
final class LocalWalBatchAdmission {
  private LocalWalBatchAdmission() {}

  static StatusCode admit(
      LocalWal wal, LocalWalRecordBatch batch, LocalWalBatchAdmissionResult result) {
    if (wal == null || batch == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    int records = batch.recordCount();
    if (records <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (wal.pendingRecordCountValue() > Long.MAX_VALUE - records) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long sequence = wal.nextJournalSequenceValue();
    if (sequence <= 0 || sequence > Long.MAX_VALUE - records + 1) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long end = wal.tailEnd();
    for (int record = 0; record < records; record++) {
      int bytes = WalRecordCodec.encodedBytes(batch.payloadBytes(record));
      if (bytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (end > Long.MAX_VALUE - bytes) return StatusCode.RESOURCE_EXHAUSTED;
      end += bytes;
    }
    result.set(end, records);
    return StatusCode.OK;
  }
}
