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

/** Atomically installed fixed checkpoint authority file. */
public final class CheckpointControlStore {
  public static final String FILE_NAME = "river.checkpoint";
  public static final int BYTES = 512;

  private static final String TEMPORARY_FILE_NAME = "river.checkpoint.tmp";
  private static final long MAGIC = 0x5249564552434b50L; // RIVERCKP
  private static final int VERSION = 1;
  private static final int DELETED_WORDS = CheckpointState.MAXIMUM_ROWS / Long.SIZE;
  private static final int DELETED_OFFSET = 72;
  private static final int CHECKSUM_OFFSET = 504;
  private static final int COMPLEMENT_OFFSET = 508;

  private final ByteBuffer bytes = ByteBuffer.allocateDirect(BYTES);
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
    if (!status.isOk() || sizeResult.sizeBytes() != BYTES) {
      file.close();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    bytes.clear();
    status = file.read(0, bytes, ioResult);
    StatusCode close = file.close();
    if (!status.isOk()) {
      return status;
    }
    if (!close.isOk()) {
      return close;
    }
    if (ioResult.bytesTransferred() != BYTES) {
      return StatusCode.CORRUPTION;
    }
    bytes.position(0);
    bytes.limit(BYTES);
    return decode(result);
  }

  public StatusCode install(DurableDirectory directory, CheckpointState state) {
    if (directory == null || state == null || !state.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    encode(state);
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
    bytes.limit(BYTES);
    status = temporary.write(0, bytes, ioResult);
    if (status.isOk() && ioResult.bytesTransferred() != BYTES) {
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

  private void encode(CheckpointState state) {
    bytes.clear();
    for (int index = 0; index < BYTES; index++) {
      bytes.put(index, (byte) 0);
    }
    putLong(0, MAGIC);
    putInt(8, VERSION);
    putInt(12, BYTES);
    putLong(16, state.database().high());
    putLong(24, state.database().low());
    putLong(32, state.walGeneration().value());
    putLong(40, state.checkpointId());
    putLong(48, state.commitSequence());
    putLong(56, state.maximumTransactionId());
    putInt(64, state.pageCount());
    putInt(68, state.rowCount());
    for (int index = 0; index < DELETED_WORDS; index++) {
      putLong(DELETED_OFFSET + index * Long.BYTES, state.deletedWord(index));
    }
    putInt(CHECKSUM_OFFSET, 0);
    putInt(COMPLEMENT_OFFSET, 0);
    int value = checksum();
    putInt(CHECKSUM_OFFSET, value);
    putInt(COMPLEMENT_OFFSET, ~value);
    bytes.position(0);
    bytes.limit(BYTES);
  }

  private StatusCode decode(CheckpointState result) {
    int stored = getInt(CHECKSUM_OFFSET);
    long databaseHigh = getLong(16);
    long databaseLow = getLong(24);
    long generation = getLong(32);
    long checkpointId = getLong(40);
    long commitSequence = getLong(48);
    long maximumTransactionId = getLong(56);
    int pageCount = getInt(64);
    int rowCount = getInt(68);
    if (getLong(0) != MAGIC
        || getInt(8) != VERSION
        || getInt(12) != BYTES
        || (databaseHigh == 0 && databaseLow == 0)
        || generation <= 0
        || checkpointId <= 0
        || commitSequence <= 0
        || maximumTransactionId <= 0
        || pageCount <= 0
        || rowCount < 0
        || rowCount > CheckpointState.MAXIMUM_ROWS
        || !reservedZero()
        || getInt(COMPLEMENT_OFFSET) != ~stored
        || checksum() != stored) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = result.set(
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
    for (int index = 0; index < DELETED_WORDS; index++) {
      result.setDeletedWord(index, getLong(DELETED_OFFSET + index * Long.BYTES));
    }
    if (result.hasDeletedRowsBeyond(rowCount)) {
      result.reset();
      return StatusCode.CORRUPTION;
    }
    return StatusCode.OK;
  }

  private boolean reservedZero() {
    for (int index = DELETED_OFFSET + DELETED_WORDS * Long.BYTES;
        index < CHECKSUM_OFFSET;
        index++) {
      if (bytes.get(index) != 0) {
        return false;
      }
    }
    return true;
  }

  private int checksum() {
    checksum.reset();
    for (int index = 0; index < CHECKSUM_OFFSET; index++) {
      checksum.update(bytes.get(index));
    }
    for (int index = CHECKSUM_OFFSET; index < BYTES; index++) {
      checksum.update(0);
    }
    return (int) checksum.getValue();
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
