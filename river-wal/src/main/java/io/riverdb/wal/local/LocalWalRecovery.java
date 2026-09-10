package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalCommitGroupCodec;
import io.riverdb.format.wal.WalFileHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalFileHeaderDecodeResult;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;

/** Recovers complete force groups; an invalid suffix is repaired before writers are admitted. */
final class LocalWalRecovery {
  private LocalWalRecovery() {}

  static StatusCode recover(LocalWal wal) {
    StatusCode status = wal.readFileSize();
    if (!status.isOk()) return status;
    long fileBytes = wal.fileSizeBytes();
    if (fileBytes < WalFileHeaderCodec.HEADER_BYTES) return StatusCode.CORRUPTION;
    ByteBuffer identity = ByteBuffer.allocate(WalFileHeaderCodec.HEADER_BYTES);
    status = wal.readExactForRecovery(0, identity);
    if (!status.isOk()) return status;
    identity.flip();
    WalFileHeaderDecodeResult decoded = new WalFileHeaderDecodeResult();
    status = WalFileHeaderCodec.decode(identity, decoded);
    if (!status.isOk()) return status;
    WalFileHeader header = decoded.header();
    if (!wal.databaseIncarnation().equals(header.databaseIncarnation())
        || !wal.walGeneration().equals(header.walGeneration())) return StatusCode.FENCED;

    long offset = WalFileHeaderCodec.HEADER_BYTES;
    long expectedSequence = 1;
    while (offset < fileBytes) {
      long groupStart = offset;
      long firstSequence = expectedSequence;
      long recordBytes = 0;
      long recordCount = 0;
      long lastSequence = 0;
      boolean invalid = false;
      boolean complete = false;
      wal.beginRecoveryGroup();
      while (offset < fileBytes) {
        StatusCode footer = wal.readFooterAt(offset, fileBytes);
        if (footer.isOk()) {
          if (invalid || recordCount == 0
              || !wal.validRecoveryFooter(
                  groupStart, recordBytes, recordCount, firstSequence, lastSequence)) {
            return repairSuffix(wal, groupStart, firstSequence, fileBytes);
          }
          wal.commitRecoveredGroup();
          offset += WalCommitGroupCodec.FOOTER_BYTES;
          complete = true;
          break;
        }
        if (footer != StatusCode.INVALID_EXTERNAL_INPUT && footer != StatusCode.CORRUPTION) {
          return footer;
        }
        if (footer == StatusCode.CORRUPTION
            || fileBytes - offset < WalRecordCodec.HEADER_BYTES) {
          return repairSuffix(wal, groupStart, firstSequence, fileBytes);
        }
        ByteBuffer record = wal.recoveryRecord();
        WalRecordHeader recordHeader = wal.recoveryHeader();
        record.clear();
        record.limit(WalRecordCodec.HEADER_BYTES);
        status = wal.readExactForRecovery(offset, record);
        if (!status.isOk()) return status;
        record.flip();
        status = WalRecordCodec.decodeHeader(record, recordHeader);
        if (!status.isOk() || recordHeader.totalBytes() > fileBytes - offset) {
          return repairSuffix(wal, groupStart, firstSequence, fileBytes);
        }
        int bytes = recordHeader.totalBytes();
        record.clear();
        record.limit(bytes);
        status = wal.readExactForRecovery(offset, record);
        if (!status.isOk()) return status;
        record.flip();
        status = WalRecordCodec.validate(record, recordHeader, wal.recoveryChecksum());
        if (!status.isOk() || expectedSequence == 0
            || recordHeader.journalSequence() != expectedSequence
            || !wal.validDecisionForRecovery(recordHeader)) {
          invalid = true;
        } else {
          wal.acceptRecoveredRecord(recordHeader);
        }
        wal.includeRecoveryRecord(record, bytes);
        recordBytes += bytes;
        recordCount++;
        lastSequence = expectedSequence;
        expectedSequence = expectedSequence == Long.MAX_VALUE ? 0 : expectedSequence + 1;
        offset += bytes;
      }
      if (!complete) return repairSuffix(wal, groupStart, firstSequence, fileBytes);
    }
    wal.finishRecovery(offset, expectedSequence);
    return StatusCode.OK;
  }

  private static StatusCode repairSuffix(
      LocalWal wal, long groupStart, long firstSequence, long fileBytes) {
    StatusCode status = LocalWalRecoverySuffix.check(wal, groupStart, firstSequence, fileBytes);
    if (!status.isOk()) return status;
    return wal.truncateTailForRecovery(groupStart, firstSequence);
  }
}
