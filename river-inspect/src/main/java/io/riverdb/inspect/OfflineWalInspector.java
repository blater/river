package io.riverdb.inspect;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalFileHeader;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.format.wal.WalFileHeaderDecodeResult;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.format.wal.WalRecordHeader;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Validates WAL headers, record framing, checksums, and decision order. */
final class OfflineWalInspector {
  private final OfflineInspectionFile file;
  private final WalFileHeaderDecodeResult fileHeader =
      new WalFileHeaderDecodeResult();
  private final WalRecordHeader recordHeader = new WalRecordHeader();
  private final CRC32C checksum = new CRC32C();
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(
      WalRecordCodec.HEADER_BYTES + WalRecordCodec.MAX_PAYLOAD_BYTES);

  OfflineWalInspector(OfflineInspectionFile inspectedFile) {
    file = inspectedFile;
  }

  StatusCode inspect(
      NioDurableDirectory directory,
      String name,
      DatabaseInspectionResult result) {
    StatusCode status = file.open(directory, name);
    if (status.isOk()) {
      status = file.readSize();
    }
    long size = file.sizeBytes();
    if (status.isOk() && size < WalFileHeaderCodec.HEADER_BYTES) {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      status = readHeader(name, result);
    }
    if (status.isOk()) {
      status = readRecords(size, result);
    }
    if (status.isOk()) {
      result.addWalFile(size);
    }
    return file.close(status);
  }

  private StatusCode readHeader(
      String name, DatabaseInspectionResult result) {
    bytes.clear();
    bytes.limit(WalFileHeaderCodec.HEADER_BYTES);
    StatusCode status = file.read(0, bytes);
    if (status.isOk()) {
      bytes.flip();
      status = WalFileHeaderCodec.decode(bytes, fileHeader);
    }
    WalFileHeader header = status.isOk() ? fileHeader.header() : null;
    if (status.isOk()
        && !result.database().equals(header.databaseIncarnation())) {
      return StatusCode.FENCED;
    }
    if (!status.isOk() || OfflinePhysicalFileNames.WAL_FILE.equals(name)) {
      return status;
    }
    long generation = OfflinePhysicalFileNames.generation(
        name, OfflinePhysicalFileNames.WAL_FILE);
    return generation <= 0 || generation != header.walGeneration().value()
        ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private StatusCode readRecords(
      long fileBytes, DatabaseInspectionResult result) {
    long offset = WalFileHeaderCodec.HEADER_BYTES;
    long expectedSequence = 1;
    long lastCommitSequence = 0;
    while (offset < fileBytes) {
      StatusCode status = readRecord(
          offset, fileBytes, expectedSequence, lastCommitSequence);
      if (!status.isOk()) {
        return status;
      }
      result.addWalRecord(
          recordHeader.journalSequence(), recordHeader.commitSequence());
      offset += recordHeader.totalBytes();
      expectedSequence++;
      if (recordHeader.decisionCode() == 1) {
        lastCommitSequence = recordHeader.commitSequence();
      }
    }
    return StatusCode.OK;
  }

  private StatusCode readRecord(
      long offset,
      long fileBytes,
      long expectedSequence,
      long lastCommitSequence) {
    if (fileBytes - offset < WalRecordCodec.HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    bytes.clear();
    bytes.limit(WalRecordCodec.HEADER_BYTES);
    StatusCode status = file.read(offset, bytes);
    if (status.isOk()) {
      bytes.flip();
      status = WalRecordCodec.decodeHeader(bytes, recordHeader);
    }
    if (status.isOk() && (recordHeader.journalSequence() != expectedSequence
        || recordHeader.totalBytes() > fileBytes - offset)) {
      return StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      bytes.clear();
      bytes.limit(recordHeader.totalBytes());
      status = file.read(offset, bytes);
    }
    if (status.isOk()) {
      bytes.flip();
      status = WalRecordCodec.validate(bytes, recordHeader, checksum);
    }
    return status.isOk() && !validDecision(recordHeader, lastCommitSequence)
        ? StatusCode.CORRUPTION : status;
  }

  private static boolean validDecision(
      WalRecordHeader header, long lastCommitSequence) {
    return switch (header.decisionCode()) {
      case 0 -> header.commitSequence() == 0;
      case 1 -> header.transactionId() > 0
          && header.commitSequence() > lastCommitSequence;
      case 2 -> header.transactionId() > 0 && header.commitSequence() == 0;
      default -> false;
    };
  }
}
