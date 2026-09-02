package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;

/** Loads and repairs the immutable page base selected by a checkpoint. */
final class IndexedCheckpointLoader {
  private final DurableDirectory directory;
  private final DurableFile primaryFile;
  private final IndexedPageSet pages;
  private final IndexedTableKernel kernel;
  private final DatabaseIncarnation database;
  private final IndexedCheckpointPageLoader pageLoader;
  private final FileSizeResult fileSize = new FileSizeResult();
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
    pageLoader = new IndexedCheckpointPageLoader(currentFile, pages, kernel, database);
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
    StatusCode close;
    try {
      status = loadOpened(checkpointFile, checkpoint, generation);
    } catch (OutOfMemoryError exhausted) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    } finally {
      close = checkpointFile.close();
    }
    if (status.isOk()) status = close;
    if (status.isOk()) status = kernel.loadCheckpointVersions(checkpoint);
    return status;
  }

  private StatusCode loadOpened(
      DurableFile checkpointFile,
      CheckpointState checkpoint,
      WalGeneration generation) {
    long expectedBytes = (long) checkpoint.pageCount() * PageCodec.PAGE_BYTES;
    StatusCode status = checkpointFile.size(fileSize);
    if (!status.isOk() || fileSize.sizeBytes() > expectedBytes) {
      return status.isOk() ? StatusCode.CORRUPTION : status;
    }
    boolean repaired = false;
    for (int pageId = 1; status.isOk() && pageId <= checkpoint.pageCount(); pageId++) {
      IndexedCheckpointPageLoader.PageLoad page = pageLoader.loadPage(
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
      status = IndexedCheckpointRows.load(kernel, checkpoint);
    }
    return status;
  }

}
