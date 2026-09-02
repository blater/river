package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DirectoryDurability;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Performs bounded I/O and atomic replacement for the authority manifest. */
final class CheckpointManifestStore {
  static final String FILE_NAME = "river.checkpoint";
  static final String TEMPORARY_FILE_NAME = "river.checkpoint.tmp";
  private final ByteBuffer bytes = ByteBuffer.allocateDirect(CheckpointManifestFormat.BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final IoResult io = new IoResult();
  private final CheckpointChecksum checksum = new CheckpointChecksum();
  private final FileSizeResult size = new FileSizeResult();
  private final DirectoryOperationResult operation = new DirectoryOperationResult();

  StatusCode read(
      DurableDirectory directory, CheckpointState state, CheckpointManifestVersion versions,
      CheckpointLogicalRowIdManifestReference logicalRowIds) {
    StatusCode status = directory.reopen(FILE_NAME, operation);
    if (!status.isOk()) return status;
    DurableFile file = operation.file();
    status = file.size(size);
    if (status.isOk() && size.sizeBytes() == CheckpointManifestFormat.BYTES) {
      bytes.clear();
      status = file.read(0, bytes, io);
      if (status.isOk() && io.bytesTransferred() != CheckpointManifestFormat.BYTES) {
        status = StatusCode.CORRUPTION;
      }
    } else if (status.isOk()) {
      status = StatusCode.CORRUPTION;
    }
    StatusCode close = file.close();
    if (status.isOk()) status = close;
    return status.isOk()
        ? CheckpointManifestFormat.decode(
            bytes, state, versions, logicalRowIds, checksum) : status;
  }

  StatusCode install(
      DurableDirectory directory, CheckpointState state, int versionPages, long versionBytes,
      int versionSlot, int cleanupSlot,
      CheckpointLogicalRowIdManifestReference logicalRowIds) {
    CheckpointManifestFormat.encode(
        bytes, state, versionPages, versionBytes, versionSlot, cleanupSlot,
        logicalRowIds, checksum);
    StatusCode status = createTemporary(directory);
    if (!status.isOk()) return status;
    DurableFile file = operation.file();
    bytes.position(0);
    bytes.limit(CheckpointManifestFormat.BYTES);
    status = file.write(0, bytes, io);
    if (status.isOk() && io.bytesTransferred() != CheckpointManifestFormat.BYTES) {
      status = StatusCode.IO_FAILURE;
    }
    if (status.isOk()) status = file.force(ForceMode.CONTENT_AND_METADATA);
    StatusCode close = file.close();
    if (status.isOk()) status = close;
    if (status.isOk()) status = directory.replace(TEMPORARY_FILE_NAME, FILE_NAME, operation);
    if (!status.isOk()) return status;
    status = directory.force(operation);
    return !status.isOk()
        && operation.durability() == DirectoryDurability.DURABLE
        ? StatusCode.OK : status;
  }

  private StatusCode createTemporary(DurableDirectory directory) {
    StatusCode status = directory.createTemporary(TEMPORARY_FILE_NAME, operation);
    if (status != StatusCode.CONFLICT) return status;
    status = directory.remove(TEMPORARY_FILE_NAME, operation);
    if (status.isOk()) status = directory.force(operation);
    return status.isOk() ? directory.createTemporary(TEMPORARY_FILE_NAME, operation) : status;
  }
}
