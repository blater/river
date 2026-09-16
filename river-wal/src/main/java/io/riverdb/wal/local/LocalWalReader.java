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
    LocalWalRecoveryState recovery = wal.recoveryState();
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
    ByteBuffer record = recovery.recordBuffer();
    record.clear();
    record.limit(WalRecordCodec.HEADER_BYTES);
    status = recovery.readExact(offset, record);
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
    status = recovery.readExact(offset, record);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    record.flip();
    status = WalRecordCodec.validate(record, result.header(), recovery.checksum());
    if (!status.isOk()) {
      return status;
    }
    ByteBuffer payload = recovery.payloadBuffer();
    payload.clear();
    payload.limit(result.header().payloadBytes());
    long nextOffset = offset + result.header().totalBytes();
    long recordEnd = nextOffset;
    if (nextOffset <= wal.durableEnd() - io.riverdb.format.wal.WalCommitGroupCodec.FOOTER_BYTES) {
      StatusCode footer = recovery.readFooterAt(nextOffset, wal.durableEnd());
      if (footer.isOk()) {
        nextOffset += io.riverdb.format.wal.WalCommitGroupCodec.FOOTER_BYTES;
      } else if (footer != StatusCode.INVALID_EXTERNAL_INPUT) {
        return footer;
      }
    }
    result.set(recordEnd, nextOffset, payload);
    return StatusCode.OK;
  }
}
