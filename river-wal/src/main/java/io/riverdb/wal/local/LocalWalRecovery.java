package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalFileHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalFileHeaderDecodeResult;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Replays and truncates the valid prefix of a local WAL during open. */
final class LocalWalRecovery {
  private LocalWalRecovery() {
  }

  static StatusCode recover(LocalWal wal) {
    StatusCode status = wal.readFileSize();
    if (!status.isOk()) {
      return status;
    }
    long fileBytes = wal.fileSizeBytes();
    if (fileBytes < WalFileHeaderCodec.HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    ByteBuffer fileHeaderBytes = ByteBuffer.allocate(WalFileHeaderCodec.HEADER_BYTES);
    status = wal.readExactForRecovery(0, fileHeaderBytes);
    if (!status.isOk()) {
      return status;
    }
    fileHeaderBytes.flip();
    WalFileHeaderDecodeResult fileHeader = new WalFileHeaderDecodeResult();
    status = WalFileHeaderCodec.decode(fileHeaderBytes, fileHeader);
    if (!status.isOk()) {
      return status;
    }
    WalFileHeader header = fileHeader.header();
    if (!wal.databaseIncarnation().equals(header.databaseIncarnation())
        || !wal.walGeneration().equals(header.walGeneration())) {
      return StatusCode.FENCED;
    }
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    long expectedSequence = 1;
    while (offset < fileBytes) {
      if (fileBytes - offset < WalRecordCodec.HEADER_BYTES) {
        return wal.truncateTailForRecovery(offset, expectedSequence);
      }
      ByteBuffer record = wal.recoveryRecord();
      WalRecordHeader recoveryHeader = wal.recoveryHeader();
      record.clear();
      record.limit(WalRecordCodec.HEADER_BYTES);
      status = wal.readExactForRecovery(offset, record);
      if (!status.isOk()) {
        return status;
      }
      record.flip();
      status = WalRecordCodec.decodeHeader(record, recoveryHeader);
      if (!status.isOk() || recoveryHeader.journalSequence() != expectedSequence) {
        return StatusCode.CORRUPTION;
      }
      if (recoveryHeader.totalBytes() > fileBytes - offset) {
        return wal.truncateTailForRecovery(offset, expectedSequence);
      }
      record.clear();
      record.limit(recoveryHeader.totalBytes());
      status = wal.readExactForRecovery(offset, record);
      if (!status.isOk()) {
        return status;
      }
      record.flip();
      status = WalRecordCodec.validate(record, recoveryHeader, wal.recoveryChecksum());
      if (!status.isOk() || !wal.validDecisionForRecovery(recoveryHeader)) {
        return StatusCode.CORRUPTION;
      }
      wal.acceptRecoveredRecord(recoveryHeader);
      offset += recoveryHeader.totalBytes();
      expectedSequence++;
    }
    wal.finishRecovery(offset, expectedSequence);
    return StatusCode.OK;
  }
}
