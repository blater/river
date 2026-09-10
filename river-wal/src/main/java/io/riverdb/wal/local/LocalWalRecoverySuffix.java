package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalCommitGroupCodec;
import io.riverdb.format.wal.WalCommitGroupHeader;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.wal.WalRecordHeader;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Checks damaged suffixes for later complete groups before permitting destructive tail repair. */
final class LocalWalRecoverySuffix {
  private LocalWalRecoverySuffix() {}

  static StatusCode check(LocalWal wal, long damagedStart, long firstSequence, long fileBytes) {
    // Recovery-only scratch. The overlap retains footers crossing a scan-buffer boundary.
    ByteBuffer scan = ByteBuffer.allocate(64 * 1024);
    WalCommitGroupHeader footer = new WalCommitGroupHeader();
    CRC32C checksum = new CRC32C();
    CRC32C recordsChecksum = new CRC32C();
    long offset = damagedStart;
    while (fileBytes - offset >= WalCommitGroupCodec.FOOTER_BYTES) {
      int bytes = (int) Math.min(scan.capacity(), fileBytes - offset);
      scan.clear();
      scan.limit(bytes);
      StatusCode status = wal.readExactForRecovery(offset, scan);
      if (!status.isOk()) return status;
      scan.flip();
      for (int index = 0; index <= bytes - WalCommitGroupCodec.FOOTER_BYTES; index++) {
        scan.position(index);
        if (!WalCommitGroupCodec.matchesMagic(scan)) continue;
        status = WalCommitGroupCodec.decodeHeader(scan, footer, checksum);
        if (!status.isOk() || footer.groupStart() <= damagedStart
            || footer.firstJournalSequence() <= firstSequence
            || footer.groupStart() + footer.recordBytes() != offset + index) continue;
        status = validateRecords(wal, footer, checksum, recordsChecksum);
        if (status.isOk()) return StatusCode.CORRUPTION;
        if (status != StatusCode.CORRUPTION) return status;
      }
      offset += bytes - WalCommitGroupCodec.FOOTER_BYTES + 1;
    }
    return StatusCode.OK;
  }

  private static StatusCode validateRecords(
      LocalWal wal, WalCommitGroupHeader footer, CRC32C checksum, CRC32C recordsChecksum) {
    ByteBuffer record = wal.recoveryRecord();
    WalRecordHeader header = wal.recoveryHeader();
    long offset = footer.groupStart();
    long end = offset + footer.recordBytes();
    long sequence = footer.firstJournalSequence();
    long remaining = footer.recordCount();
    recordsChecksum.reset();
    while (remaining > 0) {
      if (end - offset < WalRecordCodec.HEADER_BYTES) return StatusCode.CORRUPTION;
      record.clear();
      record.limit(WalRecordCodec.HEADER_BYTES);
      StatusCode status = wal.readExactForRecovery(offset, record);
      if (!status.isOk()) return status;
      record.flip();
      status = WalRecordCodec.decodeHeader(record, header);
      if (!status.isOk() || header.totalBytes() > end - offset) return StatusCode.CORRUPTION;
      int bytes = header.totalBytes();
      record.clear();
      record.limit(bytes);
      status = wal.readExactForRecovery(offset, record);
      if (!status.isOk()) return status;
      record.flip();
      status = WalRecordCodec.validate(record, header, checksum);
      if (!status.isOk() || header.journalSequence() != sequence) return StatusCode.CORRUPTION;
      recordsChecksum.update(record);
      offset += bytes;
      remaining--;
      if (remaining > 0 && sequence == Long.MAX_VALUE) return StatusCode.CORRUPTION;
      sequence++;
    }
    return offset == end && (int) recordsChecksum.getValue() == footer.groupChecksum()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
