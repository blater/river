package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.page.PageHeader;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.util.zip.CRC32C;

/** Loads and repairs the immutable page base selected by a checkpoint. */
final class IndexedCheckpointLoader {
  private final DurableDirectory directory;
  private final DurableFile primaryFile;
  private final IndexedPageSet pages;
  private final IndexedTableKernel kernel;
  private final DatabaseIncarnation database;
  private final FileSizeResult fileSize = new FileSizeResult();
  private final IoResult io = new IoResult();
  private final PageHeader header = new PageHeader();
  private final CRC32C checksum = new CRC32C();
  private final DirectoryOperationResult operation = new DirectoryOperationResult();

  IndexedCheckpointLoader(
      DurableDirectory durableDirectory,
      DurableFile currentFile,
      IndexedPageSet pageSet,
      IndexedTableKernel tableKernel,
      DatabaseIncarnation databaseIncarnation) {
    directory = durableDirectory;
    primaryFile = currentFile;
    pages = pageSet;
    kernel = tableKernel;
    database = databaseIncarnation;
  }

  StatusCode load(CheckpointState checkpoint, WalGeneration generation) {
    if (checkpoint.pageCount() <= 0
        || checkpoint.pageCount() > IndexedTableLimits.MAX_PAGES
        || checkpoint.rowCount() < 0
        || checkpoint.rowCount() > IndexedTableLimits.MAX_ROWS) {
      return StatusCode.CORRUPTION;
    }
    operation.reset();
    StatusCode status = directory.reopen(
        IndexedTableStore.checkpointFileName(checkpoint.walGeneration()), operation);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.CORRUPTION;
    }
    if (!status.isOk()) {
      return status;
    }
    DurableFile checkpointFile = operation.file();
    long expectedBytes = (long) checkpoint.pageCount() * PageCodec.PAGE_BYTES;
    status = checkpointFile.size(fileSize);
    if (!status.isOk() || fileSize.sizeBytes() > expectedBytes) {
      checkpointFile.close();
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    boolean repaired = false;
    for (int pageId = 1; status.isOk() && pageId <= checkpoint.pageCount(); pageId++) {
      PageLoad page = loadPage(
          checkpointFile, pageId, fileSize.sizeBytes(), generation);
      status = page.status;
      repaired |= page.repaired;
    }
    if (status.isOk() && repaired) {
      status = checkpointFile.truncate(expectedBytes);
      if (status.isOk()) {
        status = checkpointFile.force(ForceMode.CONTENT_AND_METADATA);
      }
    }
    if (status.isOk()) {
      status = kernel.rebuildRowLocations();
      if (status.isOk() && kernel.rowCount() != checkpoint.rowCount()) {
        status = StatusCode.CORRUPTION;
      }
    }
    StatusCode close = checkpointFile.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      kernel.loadCheckpointVersions(checkpoint);
    }
    return status;
  }

  private PageLoad loadPage(
      DurableFile checkpoint,
      int pageId,
      long checkpointBytes,
      WalGeneration generation) {
    pages.ensureBuffers(pageId);
    long pageOffset = (long) (pageId - 1) * PageCodec.PAGE_BYTES;
    boolean loaded = false;
    StatusCode status = StatusCode.OK;
    if (pageOffset + PageCodec.PAGE_BYTES <= checkpointBytes) {
      status = pages.readCurrent(checkpoint, pageId, pageOffset, io);
      if (status.isOk()) {
        loaded = io.bytesTransferred() == PageCodec.PAGE_BYTES
            && validatePage(pageId, generation.value(), generation).isOk();
      }
    }
    boolean repaired = false;
    if (status.isOk() && !loaded) {
      status = repairPage(checkpoint, pageId, pageOffset, generation);
      repaired = status.isOk();
    }
    if (status.isOk()) {
      pages.installPresent(pageId);
    }
    return new PageLoad(status, repaired);
  }

  private StatusCode repairPage(
      DurableFile checkpoint,
      int pageId,
      long pageOffset,
      WalGeneration generation) {
    if (generation.value() <= 1) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = pages.readCurrent(primaryFile, pageId, pageOffset, io);
    if (!status.isOk() || io.bytesTransferred() != PageCodec.PAGE_BYTES) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    status = validatePage(pageId, 0, generation);
    if (!status.isOk()) {
      return StatusCode.CORRUPTION;
    }
    status = pages.encodeCurrent(pageId, database, generation, 0, 0, checksum);
    if (status.isOk()) {
      status = pages.writeCurrent(checkpoint, pageId, pageOffset, io);
    }
    return status.isOk() && io.bytesTransferred() != PageCodec.PAGE_BYTES
        ? StatusCode.IO_FAILURE : status;
  }

  private StatusCode validatePage(
      int pageId, long expectedGeneration, WalGeneration generation) {
    StatusCode status = pages.validateCurrent(pageId, header, checksum);
    if (!status.isOk()
        || header.databaseHigh() != database.high()
        || header.databaseLow() != database.low()
        || header.pageId() != pageId
        || header.pageGeneration() != 1) {
      return StatusCode.CORRUPTION;
    }
    if ((expectedGeneration == 0 && header.walGeneration() >= generation.value())
        || (expectedGeneration != 0
            && header.walGeneration() != expectedGeneration)) {
      return StatusCode.CORRUPTION;
    }
    if (expectedGeneration == generation.value()
        && (header.recordStart() != 0 || header.recordEnd() != 0)) {
      return StatusCode.CORRUPTION;
    }
    return kernel.validateCurrentPage(pageId);
  }

  private record PageLoad(StatusCode status, boolean repaired) {}
}
