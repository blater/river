package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverFile;
import java.nio.ByteBuffer;

/** Read-only active-file recovery validator for the selected control lineage. */
final class AuditRecovery {
  private AuditRecovery() { }

  static StatusCode validateActive(
      RiverFile file,
      AuditFormat format,
      long firstSequence,
      long durableSequence,
      long expectedGeneration,
      long expectedInstanceHigh,
      long expectedInstanceLow,
      long expectedCredentialGeneration) {
    if (file == null || format == null || firstSequence <= 0
        || durableSequence < firstSequence - 1 || durableSequence == Long.MAX_VALUE
        || expectedGeneration <= 0 || expectedGeneration == Long.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FileSizeResult size = new FileSizeResult();
    StatusCode status = file.size(size);
    if (!status.isOk()) return status;
    long recordCount = durableSequence - firstSequence + 1;
    if (recordCount < 0 || recordCount > (Long.MAX_VALUE - format.headerBytes()) / format.eventBytes()) {
      return StatusCode.CORRUPTION;
    }
    long expected = format.headerBytes() + recordCount * (long) format.eventBytes();
    if (size.sizeBytes() != expected) return StatusCode.CORRUPTION;
    ByteBuffer header = ByteBuffer.allocateDirect(format.headerBytes());
    status = readExact(file, 0, header);
    if (!status.isOk()) return status;
    header.flip();
    status = AuditHeader.validate(header, format, expectedGeneration,
        expectedInstanceHigh, expectedInstanceLow, expectedCredentialGeneration, firstSequence);
    if (!status.isOk()) return status;
    ByteBuffer record = ByteBuffer.allocateDirect(format.eventBytes());
    for (long sequence = firstSequence; sequence <= durableSequence; sequence++) {
      record.clear();
      status = readExact(file,
          format.headerBytes() + (sequence - firstSequence) * (long) format.eventBytes(), record);
      if (!status.isOk()) return status;
      record.flip();
      status = AuditRecord.validate(record, format, sequence, expectedGeneration,
          expectedInstanceHigh, expectedInstanceLow, expectedCredentialGeneration);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  static StatusCode recoverActive(
      RiverFile file,
      AuditFormat format,
      long firstSequence,
      long expectedGeneration,
      long expectedInstanceHigh,
      long expectedInstanceLow,
      long expectedCredentialGeneration,
      RecoveryResult result) {
    if (file == null || format == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    FileSizeResult size = new FileSizeResult();
    StatusCode status = file.size(size);
    if (!status.isOk()) return status;
    long bytes = size.sizeBytes() - format.headerBytes();
    if (bytes < 0 || bytes % format.eventBytes() != 0) return StatusCode.CORRUPTION;
    long records = bytes / format.eventBytes();
    if (records > Long.MAX_VALUE - (firstSequence - 1)) return StatusCode.CORRUPTION;
    long frontier = firstSequence - 1 + records;
    status = validateActive(file, format, firstSequence, frontier, expectedGeneration,
        expectedInstanceHigh, expectedInstanceLow, expectedCredentialGeneration);
    if (status.isOk()) result.set(frontier, size.sizeBytes());
    return status;
  }

  static final class RecoveryResult {
    private long durableSequence;
    private long durableBytes;
    void reset() { durableSequence = 0; durableBytes = 0; }
    void set(long sequence, long bytes) { durableSequence = sequence; durableBytes = bytes; }
    long durableSequence() { return durableSequence; }
    long durableBytes() { return durableBytes; }
  }

  private static StatusCode readExact(RiverFile file, long position, ByteBuffer target) {
    IoResult io = new IoResult();
    while (target.hasRemaining()) {
      io.reset();
      StatusCode status = file.read(position, target, io);
      if (!status.isOk()) return status;
      if (io.bytesTransferred() <= 0) return StatusCode.CORRUPTION;
      position += io.bytesTransferred();
    }
    return StatusCode.OK;
  }
}
