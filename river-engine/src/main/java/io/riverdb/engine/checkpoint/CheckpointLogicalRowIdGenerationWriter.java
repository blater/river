package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Streams, forces, and publishes one immutable logical-row-floor generation. */
final class CheckpointLogicalRowIdGenerationWriter {
  static final String TEMPORARY_FILE_NAME = "river.checkpoint.logical-row-ids.tmp";
  static final String FILE_PREFIX = "river.checkpoint.logical-row-ids.";
  private static final int BUFFER_BYTES = 64 * 1024;

  private final ByteBuffer header = ByteBuffer
      .allocateDirect(CheckpointLogicalRowIdFormat.HEADER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final ByteBuffer records = ByteBuffer
      .allocateDirect(BUFFER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final CheckpointLogicalRowIdDigest digest = new CheckpointLogicalRowIdDigest();
  private final DirectoryOperationResult operation = new DirectoryOperationResult();
  private final IoResult io = new IoResult();

  StatusCode install(
      DurableDirectory directory, CheckpointState state, CheckpointLogicalRowIdSource source,
      int slot, int cleanupSlot, CheckpointLogicalRowIdManifestReference result) {
    if (directory == null || state == null || !state.isAvailable() || source == null
        || result == null || slot < 0 || slot > 1 || cleanupSlot < -1 || cleanupSlot > 1
        || cleanupSlot == slot) return StatusCode.INVALID_EXTERNAL_INPUT;
    int count = source.floorCount();
    long fileBytes = CheckpointLogicalRowIdFormat.fileBytes(count);
    if (fileBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    CheckpointLogicalRowIdFormat.encodeHeader(header, state, count, fileBytes);
    digest.reset(header);
    StatusCode status = createTemporary(directory);
    if (!status.isOk()) return status;
    DurableFile file = operation.file();
    status = writeRecords(file, source, count);
    if (status.isOk()) status = finishFile(file);
    StatusCode close = file.close();
    if (status.isOk()) status = close;
    if (!status.isOk()) return status;
    status = directory.rename(TEMPORARY_FILE_NAME, fileName(slot), operation);
    if (status.isOk()) status = directory.force(operation);
    if (status.isOk()) result.set(count, fileBytes, digest.value(), slot, cleanupSlot);
    return status;
  }

  private StatusCode writeRecords(
      DurableFile file, CheckpointLogicalRowIdSource source, int count) {
    source.rewind();
    long previous = 0;
    int emitted = 0;
    long offset = CheckpointLogicalRowIdFormat.HEADER_BYTES;
    while (emitted < count) {
      records.clear();
      int chunk = Math.min(count - emitted,
          records.capacity() / CheckpointLogicalRowIdFormat.RECORD_BYTES);
      for (int index = 0; index < chunk; index++) {
        long objectId = source.nextObjectId();
        long floor = source.nextExclusive();
        if (objectId <= previous || floor <= 0) return StatusCode.CORRUPTION;
        records.putLong(objectId);
        records.putLong(floor);
        previous = objectId;
      }
      int bytes = chunk * CheckpointLogicalRowIdFormat.RECORD_BYTES;
      digest.update(records, bytes);
      records.flip();
      StatusCode status = file.write(offset, records, io);
      if (!status.isOk()) return status;
      if (io.bytesTransferred() != bytes) return StatusCode.IO_FAILURE;
      emitted += chunk;
      offset += bytes;
    }
    return source.nextObjectId() == -1 ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode finishFile(DurableFile file) {
    CheckpointLogicalRowIdFormat.storeDigest(header, digest.value());
    header.position(0);
    header.limit(CheckpointLogicalRowIdFormat.HEADER_BYTES);
    StatusCode status = file.write(0, header, io);
    if (!status.isOk()) return status;
    if (io.bytesTransferred() != CheckpointLogicalRowIdFormat.HEADER_BYTES) {
      return StatusCode.IO_FAILURE;
    }
    return file.force(ForceMode.CONTENT_AND_METADATA);
  }

  private StatusCode createTemporary(DurableDirectory directory) {
    StatusCode status = directory.createTemporary(TEMPORARY_FILE_NAME, operation);
    if (status != StatusCode.CONFLICT) return status;
    status = directory.remove(TEMPORARY_FILE_NAME, operation);
    if (status.isOk()) status = directory.force(operation);
    return status.isOk() ? directory.createTemporary(TEMPORARY_FILE_NAME, operation) : status;
  }

  static String fileName(int slot) {
    return FILE_PREFIX + slot;
  }
}
