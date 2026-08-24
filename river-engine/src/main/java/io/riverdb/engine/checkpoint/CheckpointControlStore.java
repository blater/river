package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Atomically installed versioned checkpoint authority file. */
public final class CheckpointControlStore {
  public static final String FILE_NAME = "river.checkpoint";
  private static final int ROW_ENTRY_BYTES = 16;
  private static final int ROWS_OFFSET = 72;
  public static final int BYTES = ROWS_OFFSET
      + CheckpointState.MAXIMUM_ROWS * ROW_ENTRY_BYTES + 8;

  private static final String TEMPORARY_FILE_NAME = "river.checkpoint.tmp";
  private static final long MAGIC = 0x5249564552434b50L; // RIVERCKP
  private static final int VERSION = 2;
  private static final int VERSION_ONE = 1;
  private static final int VERSION_ONE_BYTES = 512;
  private static final int VERSION_ONE_MAXIMUM_ROWS = 2048;
  private static final int VERSION_ONE_DELETED_WORDS = VERSION_ONE_MAXIMUM_ROWS / Long.SIZE;
  private static final int VERSION_ONE_DELETED_OFFSET = 72;
  private static final int VERSION_ONE_CHECKSUM_OFFSET = 504;

  private ByteBuffer bytes = ByteBuffer.allocateDirect(BYTES);
  private final IoResult ioResult = new IoResult();
  private final FileSizeResult sizeResult = new FileSizeResult();
  private final CRC32C checksum = new CRC32C();
  private final DirectoryOperationResult operation = new DirectoryOperationResult();

  public StatusCode read(DurableDirectory directory, CheckpointState result) {
    if (directory == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = directory.reopen(FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    DurableFile file = operation.file();
    status = file.size(sizeResult);
    long fileBytes = sizeResult.sizeBytes();
    if (!status.isOk()
        || fileBytes < VERSION_ONE_BYTES
        || fileBytes > Integer.MAX_VALUE) {
      file.close();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    ensureCapacity((int) fileBytes);
    bytes.clear();
    bytes.limit((int) fileBytes);
    status = file.read(0, bytes, ioResult);
    StatusCode close = file.close();
    if (!status.isOk()) {
      return status;
    }
    if (!close.isOk()) {
      return close;
    }
    if (ioResult.bytesTransferred() != fileBytes) {
      return StatusCode.CORRUPTION;
    }
    bytes.position(0);
    bytes.limit((int) fileBytes);
    return decode(result);
  }

  public StatusCode install(DurableDirectory directory, CheckpointState state) {
    if (directory == null || state == null || !state.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode encodeStatus = encode(state);
    if (!encodeStatus.isOk()) {
      return encodeStatus;
    }
    StatusCode status = directory.createTemporary(TEMPORARY_FILE_NAME, operation);
    if (status == StatusCode.CONFLICT) {
      status = directory.remove(TEMPORARY_FILE_NAME, operation);
      if (status.isOk()) {
        status = directory.force(operation);
      }
      if (status.isOk()) {
        status = directory.createTemporary(TEMPORARY_FILE_NAME, operation);
      }
    }
    if (!status.isOk()) {
      return status;
    }
    DurableFile temporary = operation.file();
    bytes.position(0);
    int recordBytes = bytes.limit();
    bytes.position(0);
    bytes.limit(recordBytes);
    status = temporary.write(0, bytes, ioResult);
    if (status.isOk() && ioResult.bytesTransferred() != recordBytes) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) {
      status = temporary.force(ForceMode.CONTENT_AND_METADATA);
    }
    StatusCode close = temporary.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      status = directory.replace(TEMPORARY_FILE_NAME, FILE_NAME, operation);
    }
    if (status.isOk()) {
      status = directory.force(operation);
    }
    return status;
  }

  private StatusCode encode(CheckpointState state) {
    long recordBytesLong = Math.max(
        BYTES, (long) ROWS_OFFSET + state.rowCount() * ROW_ENTRY_BYTES + 8);
    if (recordBytesLong > Integer.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int recordBytes = (int) recordBytesLong;
    ensureCapacity(recordBytes);
    bytes.clear();
    for (int index = 0; index < recordBytes; index++) {
      bytes.put(index, (byte) 0);
    }
    putLong(0, MAGIC);
    putInt(8, VERSION);
    putInt(12, recordBytes);
    putLong(16, state.database().high());
    putLong(24, state.database().low());
    putLong(32, state.walGeneration().value());
    putLong(40, state.checkpointId());
    putLong(48, state.commitSequence());
    putLong(56, state.maximumTransactionId());
    putInt(64, state.pageCount());
    putInt(68, (int) state.rowCount());
    for (long rowId = 1; rowId <= state.rowCount(); rowId++) {
      long offsetLong = ROWS_OFFSET + (rowId - 1) * ROW_ENTRY_BYTES;
      if (offsetLong > Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
      int offset = (int) offsetLong;
      putLong(offset, state.rowCommitSequence(rowId));
      putInt(offset + 8, (int) state.previousRowId(rowId));
      putInt(offset + 12, state.isDeleted(rowId) ? 1 : 0);
    }
    int checksumOffset = recordBytes - 8;
    putInt(checksumOffset, 0);
    putInt(checksumOffset + 4, 0);
    int value = checksum(recordBytes);
    putInt(checksumOffset, value);
    putInt(checksumOffset + 4, ~value);
    bytes.position(0);
    bytes.limit(recordBytes);
    return StatusCode.OK;
  }

  private StatusCode decode(CheckpointState result) {
    int version = getInt(8);
    int recordBytes = getInt(12);
    if (version == VERSION_ONE && recordBytes == VERSION_ONE_BYTES) {
      return decodeVersionOne(result);
    }
    if (version != VERSION || recordBytes != bytes.limit() || recordBytes < BYTES) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = decodeHeader(result, VERSION, recordBytes);
    if (!status.isOk()) {
      return status;
    }
    int checksumOffset = recordBytes - 8;
    int stored = getInt(checksumOffset);
    if (getInt(checksumOffset + 4) != ~stored || checksum(recordBytes) != stored) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    for (long rowId = 1; rowId <= result.rowCount(); rowId++) {
      long offsetLong = ROWS_OFFSET + (rowId - 1) * ROW_ENTRY_BYTES;
      if (offsetLong > Integer.MAX_VALUE) {
        result.reset();
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      int offset = (int) offsetLong;
      int flags = getInt(offset + 12);
      if ((flags & ~1) != 0) {
        result.reset();
        return StatusCode.CORRUPTION;
      }
      status = result.setRowVersion(
        rowId, getLong(offset), Integer.toUnsignedLong(getInt(offset + 8)), flags == 1);
      if (!status.isOk()) {
        result.reset();
        return StatusCode.CORRUPTION;
      }
    }
    long unusedOffsetLong = ROWS_OFFSET + result.rowCount() * ROW_ENTRY_BYTES;
    if (unusedOffsetLong > Integer.MAX_VALUE) {
      result.reset();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int unusedOffset = (int) unusedOffsetLong;
    if (!zeroRange(unusedOffset, checksumOffset)) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private StatusCode decodeVersionOne(CheckpointState result) {
    StatusCode status = decodeHeader(result, VERSION_ONE, VERSION_ONE_BYTES);
    if (!status.isOk() || result.rowCount() > VERSION_ONE_MAXIMUM_ROWS) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    int stored = getInt(VERSION_ONE_CHECKSUM_OFFSET);
    if (getInt(VERSION_ONE_CHECKSUM_OFFSET + 4) != ~stored
        || checksum(VERSION_ONE_BYTES) != stored
        || !zeroRange(
            VERSION_ONE_DELETED_OFFSET + VERSION_ONE_DELETED_WORDS * Long.BYTES,
            VERSION_ONE_CHECKSUM_OFFSET)) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    for (int rowId = 1; rowId <= VERSION_ONE_MAXIMUM_ROWS; rowId++) {
      int bit = rowId - 1;
      boolean deleted = (getLong(
          VERSION_ONE_DELETED_OFFSET + (bit >>> 6) * Long.BYTES)
          & 1L << (bit & 63)) != 0;
      if (rowId > result.rowCount()) {
        if (deleted) {
          result.reset();
          return StatusCode.CORRUPTION;
        }
      } else if (deleted) {
        status = result.setDeleted(rowId);
        if (!status.isOk()) {
          result.reset();
          return StatusCode.CORRUPTION;
        }
      }
    }
    return StatusCode.OK;
  }

  private StatusCode decodeHeader(
      CheckpointState result,
      int expectedVersion,
      int expectedBytes) {
    long databaseHigh = getLong(16);
    long databaseLow = getLong(24);
    long generation = getLong(32);
    long checkpointId = getLong(40);
    long commitSequence = getLong(48);
    long maximumTransactionId = getLong(56);
    int pageCount = getInt(64);
    int rowCount = getInt(68);
    if (getLong(0) != MAGIC
        || getInt(8) != expectedVersion
        || getInt(12) != expectedBytes
        || (databaseHigh == 0 && databaseLow == 0)
        || generation <= 0
        || checkpointId <= 0
        || commitSequence <= 0
        || maximumTransactionId <= 0
        || pageCount <= 0
        || rowCount < 0
        || rowCount > CheckpointState.MAXIMUM_RUNTIME_ROWS) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = rowCount <= CheckpointState.MAXIMUM_ROWS
        ? result.set(
            DatabaseIncarnation.of(databaseHigh, databaseLow),
            WalGeneration.of(generation),
            checkpointId,
            commitSequence,
            maximumTransactionId,
            pageCount,
            rowCount)
        : result.setLarge(
        DatabaseIncarnation.of(databaseHigh, databaseLow),
        WalGeneration.of(generation),
        checkpointId,
        commitSequence,
        maximumTransactionId,
        pageCount,
        rowCount);
    if (!status.isOk()) {
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private boolean zeroRange(int start, int end) {
    for (int index = start; index < end; index++) {
      if (bytes.get(index) != 0) {
        return false;
      }
    }
    return true;
  }

  private int checksum(int recordBytes) {
    int checksumOffset = recordBytes - 8;
    checksum.reset();
    for (int index = 0; index < checksumOffset; index++) {
      checksum.update(bytes.get(index));
    }
    for (int index = checksumOffset; index < recordBytes; index++) {
      checksum.update(0);
    }
    return (int) checksum.getValue();
  }

  private void ensureCapacity(int required) {
    if (bytes.capacity() >= required) return;
    bytes = ByteBuffer.allocateDirect(required);
  }

  private void putInt(int offset, int value) {
    bytes.put(offset, (byte) value);
    bytes.put(offset + 1, (byte) (value >>> 8));
    bytes.put(offset + 2, (byte) (value >>> 16));
    bytes.put(offset + 3, (byte) (value >>> 24));
  }

  private int getInt(int offset) {
    return Byte.toUnsignedInt(bytes.get(offset))
        | Byte.toUnsignedInt(bytes.get(offset + 1)) << 8
        | Byte.toUnsignedInt(bytes.get(offset + 2)) << 16
        | Byte.toUnsignedInt(bytes.get(offset + 3)) << 24;
  }

  private void putLong(int offset, long value) {
    putInt(offset, (int) value);
    putInt(offset + 4, (int) (value >>> 32));
  }

  private long getLong(int offset) {
    return Integer.toUnsignedLong(getInt(offset))
        | Integer.toUnsignedLong(getInt(offset + 4)) << 32;
  }
}
