package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Opens and validates the sparse-generation header and rooted page index. */
final class CheckpointVersionGenerationReader {
  private final ByteBuffer header = ByteBuffer
      .allocateDirect(CheckpointVersionGenerationFormat.HEADER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final DirectoryOperationResult operation = new DirectoryOperationResult();
  private final FileSizeResult size = new FileSizeResult();
  private final IoResult io = new IoResult();
  private final CheckpointChecksum checksum = new CheckpointChecksum();
  private ByteBuffer index = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);

  StatusCode open(
      DurableDirectory directory, CheckpointState state, CheckpointManifestVersion manifest) {
    StatusCode status = directory.reopen(
        CheckpointVersionGenerationWriter.fileName(manifest.slot()), operation);
    if (!status.isOk()) return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    DurableFile file = operation.file();
    status = readHeader(file, state, manifest);
    if (!status.isOk()) {
      file.close();
      return status;
    }
    int pages = manifest.pageCount();
    long[] pageIds;
    long[] offsets;
    try {
      pageIds = new long[pages];
      offsets = new long[pages];
    } catch (OutOfMemoryError exhausted) {
      file.close();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = readIndex(file, state, pageIds, offsets);
    if (!status.isOk()) {
      file.close();
      return status;
    }
    status = state.attachVersionDirectory(file, pageIds, offsets, pages);
    if (!status.isOk()) file.close();
    return status;
  }

  private StatusCode readHeader(
      DurableFile file, CheckpointState state, CheckpointManifestVersion manifest) {
    StatusCode status = file.size(size);
    if (!status.isOk()) return status;
    if (size.sizeBytes() != manifest.fileBytes()) return StatusCode.CORRUPTION;
    header.clear();
    status = file.read(0, header, io);
    if (!status.isOk()) return status;
    if (io.bytesTransferred() != CheckpointVersionGenerationFormat.HEADER_BYTES) {
      return StatusCode.CORRUPTION;
    }
    return CheckpointVersionGenerationFormat.validHeader(
        header, state, manifest.pageCount(), manifest.fileBytes(), checksum)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private StatusCode readIndex(
      DurableFile file, CheckpointState state, long[] pageIds, long[] offsets) {
    int indexBytes = pageIds.length * CheckpointVersionGenerationFormat.INDEX_ENTRY_BYTES;
    try {
      if (index.capacity() < indexBytes) {
        index = ByteBuffer.allocateDirect(indexBytes).order(ByteOrder.LITTLE_ENDIAN);
      }
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    index.clear();
    index.limit(indexBytes);
    StatusCode status = file.read(CheckpointVersionGenerationFormat.HEADER_BYTES, index, io);
    if (!status.isOk()) return status;
    if (io.bytesTransferred() != indexBytes) return StatusCode.CORRUPTION;
    return CheckpointVersionGenerationFormat.validIndex(
        header, index, state, pageIds, offsets, checksum)
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
