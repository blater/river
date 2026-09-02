package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Validates and atomically loads one immutable logical-row-floor generation. */
final class CheckpointLogicalRowIdGenerationReader {
  private static final int BUFFER_BYTES = 64 * 1024;
  private final ByteBuffer header = ByteBuffer
      .allocateDirect(CheckpointLogicalRowIdFormat.HEADER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final ByteBuffer records = ByteBuffer
      .allocateDirect(BUFFER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final CheckpointLogicalRowIdDigest digest = new CheckpointLogicalRowIdDigest();
  private final DirectoryOperationResult operation = new DirectoryOperationResult();
  private final FileSizeResult size = new FileSizeResult();
  private final IoResult io = new IoResult();

  StatusCode open(
      DurableDirectory directory, CheckpointState state,
      CheckpointLogicalRowIdManifestReference reference,
      CheckpointLogicalRowIdDirectory result) {
    StatusCode status = validateReference(directory, state, reference, result);
    if (!status.isOk()) return status;
    status = directory.reopen(
        CheckpointLogicalRowIdGenerationWriter.fileName(reference.slot()), operation);
    if (!status.isOk()) return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    DurableFile file = operation.file();
    status = readHeader(file, state, reference);
    if (status.isOk()) status = result.beginLoad(reference.count());
    if (status.isOk()) status = readRecords(file, reference, result);
    StatusCode close = file.close();
    if (status.isOk()) status = close;
    if (!status.isOk()) {
      result.discardLoad();
      return status;
    }
    result.publishLoad(reference.count());
    return StatusCode.OK;
  }

  private StatusCode validateReference(
      DurableDirectory directory, CheckpointState state,
      CheckpointLogicalRowIdManifestReference reference,
      CheckpointLogicalRowIdDirectory result) {
    if (directory == null || state == null || !state.isAvailable()
        || reference == null || result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    long expected = CheckpointLogicalRowIdFormat.fileBytes(reference.count());
    return reference.slot() < 0 || reference.slot() > 1 || expected < 0
        || reference.fileBytes() != expected ? StatusCode.CORRUPTION : StatusCode.OK;
  }

  private StatusCode readHeader(
      DurableFile file, CheckpointState state,
      CheckpointLogicalRowIdManifestReference reference) {
    StatusCode status = file.size(size);
    if (!status.isOk()) return status;
    if (size.sizeBytes() != reference.fileBytes()) return StatusCode.CORRUPTION;
    header.clear();
    status = file.read(0, header, io);
    if (!status.isOk()) return status;
    if (io.bytesTransferred() != CheckpointLogicalRowIdFormat.HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    return CheckpointLogicalRowIdFormat.validHeader(header, state, reference)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode readRecords(
      DurableFile file, CheckpointLogicalRowIdManifestReference reference,
      CheckpointLogicalRowIdDirectory result) {
    digest.reset(header);
    long previous = 0;
    int loaded = 0;
    long offset = CheckpointLogicalRowIdFormat.HEADER_BYTES;
    while (loaded < reference.count()) {
      int chunk = Math.min(reference.count() - loaded,
          records.capacity() / CheckpointLogicalRowIdFormat.RECORD_BYTES);
      int bytes = chunk * CheckpointLogicalRowIdFormat.RECORD_BYTES;
      records.clear();
      records.limit(bytes);
      StatusCode status = file.read(offset, records, io);
      if (!status.isOk()) return status;
      if (io.bytesTransferred() != bytes) return StatusCode.CORRUPTION;
      digest.update(records, bytes);
      for (int index = 0; index < chunk; index++) {
        int position = index * CheckpointLogicalRowIdFormat.RECORD_BYTES;
        long objectId = records.getLong(position);
        long floor = records.getLong(position + Long.BYTES);
        if (objectId <= previous || floor <= 0) return StatusCode.CORRUPTION;
        result.load(loaded + index, objectId, floor);
        previous = objectId;
      }
      loaded += chunk;
      offset += bytes;
    }
    return digest.value() == reference.digest() ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
