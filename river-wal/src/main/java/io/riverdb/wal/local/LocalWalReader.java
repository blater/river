package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalRecordCodec;
import java.nio.ByteBuffer;

/** Reads and validates one record from the durable WAL frontier. */
final class LocalWalReader {
  private LocalWalReader() {
  }

  static StatusCode read(LocalWal wal, long offset, LocalWalReadResult result) {
    if (result == null
        || offset < WalFileHeaderCodec.HEADER_BYTES
        || offset >= wal.durableEnd()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = wal.admissionStatus();
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer record = wal.recoveryRecord();
    record.clear();
    record.limit(WalRecordCodec.HEADER_BYTES);
    status = wal.readExactForRecovery(offset, record);
    if (!status.isOk()) {
      return status;
    }
    record.flip();
    status = WalRecordCodec.decodeHeader(record, result.header());
    if (!status.isOk() || offset + result.header().totalBytes() > wal.durableEnd()) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    record.clear();
    record.limit(result.header().totalBytes());
    status = wal.readExactForRecovery(offset, record);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    record.flip();
    status = WalRecordCodec.validate(record, result.header(), wal.recoveryChecksum());
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = wal.readPayloadBuffer();
    payload.clear();
    payload.limit(result.header().payloadBytes());
    result.set(offset + result.header().totalBytes(), payload);
    return StatusCode.OK;
  }
}
