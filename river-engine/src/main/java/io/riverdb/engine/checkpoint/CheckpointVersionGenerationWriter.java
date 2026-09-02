package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds and publishes one immutable sparse version generation. */
final class CheckpointVersionGenerationWriter {
  static final String TEMPORARY_FILE_NAME = "river.checkpoint.versions.tmp";
  static final String FILE_PREFIX = "river.checkpoint.versions.";
  private final ByteBuffer header = ByteBuffer
      .allocateDirect(CheckpointVersionGenerationFormat.HEADER_BYTES)
      .order(ByteOrder.LITTLE_ENDIAN);
  private final CheckpointVersionPageEncoder pageEncoder = new CheckpointVersionPageEncoder();
  private final DirectoryOperationResult operation = new DirectoryOperationResult();
  private final IoResult io = new IoResult();
  private final CheckpointChecksum checksum = new CheckpointChecksum();
  private ByteBuffer index = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
  private int pageCount;
  private long fileBytes;
  private long dataOffset;

  StatusCode install(DurableDirectory directory, CheckpointState state, int slot) {
    if (slot < 0 || slot > 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = prepare(state);
    if (!status.isOk()) return status;
    status = createTemporary(directory);
    if (!status.isOk()) return status;
    DurableFile file = operation.file();
    status = writePages(file, state);
    if (status.isOk()) status = finishFile(file, state);
    StatusCode close = file.close();
    if (status.isOk()) status = close;
    if (!status.isOk()) return status;
    if (pageCount == 0) return discardEmpty(directory);
    status = directory.rename(
        TEMPORARY_FILE_NAME, fileName(slot), operation);
    return status.isOk() ? directory.force(operation) : status;
  }

  int pageCount() { return pageCount; }
  long fileBytes() { return pageCount == 0 ? 0 : fileBytes; }

  private StatusCode prepare(CheckpointState state) {
    int upperBound = state.versionPageCountUpperBound();
    long maximum = (CheckpointState.MAXIMUM_RUNTIME_ROWS
        + CheckpointVersionFormat.PAGE_ROWS - 1) / CheckpointVersionFormat.PAGE_ROWS;
    long indexBytes = (long) upperBound * CheckpointVersionGenerationFormat.INDEX_ENTRY_BYTES;
    if (upperBound < 0 || upperBound > maximum || indexBytes > Integer.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    try {
      if (index.capacity() < indexBytes) {
        index = ByteBuffer.allocateDirect((int) indexBytes).order(ByteOrder.LITTLE_ENDIAN);
      }
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    index.clear();
    index.limit((int) indexBytes);
    pageCount = 0;
    dataOffset = CheckpointVersionGenerationFormat.HEADER_BYTES + indexBytes;
    fileBytes = dataOffset;
    return StatusCode.OK;
  }

  private StatusCode writePages(DurableFile file, CheckpointState state) {
    long previousPageId = -1;
    state.resetVersionPages();
    while (true) {
      long pageId = state.nextVersionPageId();
      if (pageId < 0) return StatusCode.OK;
      if (pageId <= previousPageId || pageId > (state.rowCount() - 1)
          >>> CheckpointVersionFormat.PAGE_SHIFT) return StatusCode.CORRUPTION;
      previousPageId = pageId;
      StatusCode status = pageEncoder.encode(state, pageId);
      if (!status.isOk()) return status;
      if (!pageEncoder.hasExceptions()) continue;
      if ((long) (pageCount + 1) * CheckpointVersionGenerationFormat.INDEX_ENTRY_BYTES
          > dataOffset - CheckpointVersionGenerationFormat.HEADER_BYTES) {
        return StatusCode.CORRUPTION;
      }
      index.putLong(pageCount * CheckpointVersionGenerationFormat.INDEX_ENTRY_BYTES, pageId);
      index.putLong(pageCount * CheckpointVersionGenerationFormat.INDEX_ENTRY_BYTES
          + Long.BYTES, fileBytes);
      status = file.write(fileBytes, pageEncoder.bytes(), io);
      if (!status.isOk()) return status;
      if (io.bytesTransferred() != CheckpointVersionFormat.SEGMENT_BYTES) {
        return StatusCode.IO_FAILURE;
      }
      pageCount++;
      fileBytes += CheckpointVersionFormat.SEGMENT_BYTES;
    }
  }

  private StatusCode finishFile(DurableFile file, CheckpointState state) {
    int indexBytes = pageCount * CheckpointVersionGenerationFormat.INDEX_ENTRY_BYTES;
    if (indexBytes > 0) {
      index.position(0);
      index.limit(indexBytes);
      StatusCode status = file.write(
          CheckpointVersionGenerationFormat.HEADER_BYTES, index, io);
      if (!status.isOk()) return status;
      if (io.bytesTransferred() != indexBytes) return StatusCode.IO_FAILURE;
    }
    CheckpointVersionGenerationFormat.encodeHeader(
        header, index, state, pageCount, dataOffset, fileBytes, checksum);
    header.position(0);
    header.limit(CheckpointVersionGenerationFormat.HEADER_BYTES);
    StatusCode status = file.write(0, header, io);
    if (!status.isOk()) return status;
    if (io.bytesTransferred() != CheckpointVersionGenerationFormat.HEADER_BYTES) {
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

  private StatusCode discardEmpty(DurableDirectory directory) {
    StatusCode status = directory.remove(TEMPORARY_FILE_NAME, operation);
    return status.isOk() ? directory.force(operation) : status;
  }

  static String fileName(int slot) { return FILE_PREFIX + slot; }
}
