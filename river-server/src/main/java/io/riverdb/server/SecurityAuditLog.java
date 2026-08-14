package io.riverdb.server;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/** Fixed-capacity forced audit of authentication and statement admission. */
final class SecurityAuditLog {
  static final int AUTHENTICATION = 0;

  private static final String FILE_NAME = "river.security-audit";
  private static final int MAGIC = 0x52534155;
  private static final int VERSION = 1;
  private static final int RECORD_BYTES = 40;
  private static final int ALLOWED = 1;
  private static final int DENIED = 2;

  private final NioDurableDirectory directory;
  private final DurableFile file;
  private final int maximumRecords;
  private final ByteBuffer record = ByteBuffer.allocateDirect(RECORD_BYTES)
      .order(ByteOrder.BIG_ENDIAN);
  private final IoResult io = new IoResult();
  private int records;
  private boolean closed;

  private SecurityAuditLog(
      NioDurableDirectory openedDirectory,
      DurableFile openedFile,
      int maximumAuditRecords) {
    directory = openedDirectory;
    file = openedFile;
    maximumRecords = maximumAuditRecords;
  }

  static StatusCode open(
      Path root,
      int maximumRecords,
      SecurityAuditOpenResult result) {
    if (root == null || maximumRecords <= 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    StatusCode status = NioDurableDirectory.openExisting(
        root,
        new FatalStateFence(),
        new NioIoCounters(),
        1,
        directoryResult);
    if (!status.isOk()) {
      return status;
    }
    NioDurableDirectory directory = directoryResult.directory();
    DirectoryOperationResult opened = new DirectoryOperationResult();
    status = directory.reopen(FILE_NAME, opened);
    boolean created = status == StatusCode.CONFLICT;
    if (created) {
      status = directory.createFile(FILE_NAME, opened);
    }
    if (!status.isOk()) {
      directory.close();
      return status;
    }
    DurableFile file = opened.file();
    if (created) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
      if (status.isOk()) {
        DirectoryOperationResult forced = new DirectoryOperationResult();
        status = directory.force(forced);
      }
    }
    SecurityAuditLog audit = new SecurityAuditLog(directory, file, maximumRecords);
    if (status.isOk()) {
      status = audit.validateExisting();
    }
    if (!status.isOk()) {
      audit.close();
      return status;
    }
    result.set(audit);
    return StatusCode.OK;
  }

  synchronized StatusCode append(
      long principalId,
      int operation,
      boolean allowed) {
    if (closed) {
      return StatusCode.CLOSED;
    }
    if (principalId <= 0 || !validOperation(operation)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (records >= maximumRecords) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long sequence = (long) records + 1;
    int decision = allowed ? ALLOWED : DENIED;
    int checksum = checksum(sequence, principalId, operation, decision);
    record.clear();
    record.putInt(MAGIC);
    record.putInt(VERSION);
    record.putLong(sequence);
    record.putLong(principalId);
    record.putInt(operation);
    record.putInt(decision);
    record.putInt(checksum);
    record.putInt(0);
    record.flip();
    StatusCode status = writeExact((long) records * RECORD_BYTES);
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      records++;
    }
    return status;
  }

  synchronized int recordCount() {
    return records;
  }

  synchronized StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    StatusCode status = file.close();
    StatusCode directoryStatus = directory.close();
    closed = true;
    return status.isOk() ? directoryStatus : status;
  }

  private StatusCode validateExisting() {
    FileSizeResult size = new FileSizeResult();
    StatusCode status = file.size(size);
    long bytes = size.sizeBytes();
    if (!status.isOk()) {
      return status;
    }
    if (bytes < 0
        || bytes % RECORD_BYTES != 0
        || bytes / RECORD_BYTES > maximumRecords) {
      return StatusCode.CORRUPTION;
    }
    int count = (int) (bytes / RECORD_BYTES);
    for (int index = 0; index < count; index++) {
      record.clear();
      status = readExact((long) index * RECORD_BYTES);
      if (!status.isOk()) {
        return status;
      }
      record.flip();
      int magic = record.getInt();
      int version = record.getInt();
      long sequence = record.getLong();
      long principalId = record.getLong();
      int operation = record.getInt();
      int decision = record.getInt();
      int storedChecksum = record.getInt();
      int reserved = record.getInt();
      if (magic != MAGIC
          || version != VERSION
          || sequence != (long) index + 1
          || principalId <= 0
          || !validOperation(operation)
          || decision != ALLOWED && decision != DENIED
          || storedChecksum != checksum(sequence, principalId, operation, decision)
          || reserved != 0) {
        return StatusCode.CORRUPTION;
      }
    }
    records = count;
    return StatusCode.OK;
  }

  private StatusCode readExact(long position) {
    while (record.hasRemaining()) {
      int remaining = record.remaining();
      io.reset();
      StatusCode status = file.read(position, record, io);
      if (!status.isOk()) {
        return status;
      }
      int transferred = io.bytesTransferred();
      if (transferred <= 0 || transferred > remaining) {
        return StatusCode.CORRUPTION;
      }
      position += transferred;
    }
    return StatusCode.OK;
  }

  private StatusCode writeExact(long position) {
    while (record.hasRemaining()) {
      int remaining = record.remaining();
      io.reset();
      StatusCode status = file.write(position, record, io);
      if (!status.isOk()) {
        return status;
      }
      int transferred = io.bytesTransferred();
      if (transferred <= 0 || transferred > remaining) {
        return StatusCode.IO_FAILURE;
      }
      position += transferred;
    }
    return StatusCode.OK;
  }

  private static boolean validOperation(int operation) {
    return operation == AUTHENTICATION
        || operation == SessionPermissions.READ
        || operation == SessionPermissions.WRITE
        || operation == SessionPermissions.SCHEMA
        || operation == SessionPermissions.ADMIN;
  }

  private static int checksum(
      long sequence,
      long principalId,
      int operation,
      int decision) {
    int hash = mixInt(0x811c9dc5, MAGIC);
    hash = mixInt(hash, VERSION);
    hash = mixLong(hash, sequence);
    hash = mixLong(hash, principalId);
    hash = mixInt(hash, operation);
    return mixInt(hash, decision);
  }

  private static int mixLong(int hash, long value) {
    hash = mixInt(hash, (int) (value >>> 32));
    return mixInt(hash, (int) value);
  }

  private static int mixInt(int hash, int value) {
    for (int shift = 24; shift >= 0; shift -= 8) {
      hash ^= value >>> shift & 0xff;
      hash *= 0x01000193;
    }
    return hash;
  }
}
