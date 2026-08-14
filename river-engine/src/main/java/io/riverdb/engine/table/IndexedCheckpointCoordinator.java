package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.format.page.PageCodec;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.wal.local.LocalWal;
import java.util.zip.CRC32C;

/** Owns table page flushing and checkpoint-lineage publication. */
final class IndexedCheckpointCoordinator {
  private final DurableDirectory directory;
  private final DurableFile file;
  private final LocalWal wal;
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final DatabaseIncarnation database;
  private final IndexedStorePhase phase;
  private final IndexedCheckpointWriter writer;
  private final IndexedCheckpointLoader loader;
  private final CRC32C checksum = new CRC32C();
  private final IoResult ioResult = new IoResult();
  private WalGeneration generation;
  private boolean failed;

  IndexedCheckpointCoordinator(
      DurableDirectory durableDirectory,
      DurableFile durableFile,
      LocalWal localWal,
      IndexedTableKernel tableKernel,
      IndexedPageSet pageSet,
      DatabaseIncarnation databaseIncarnation,
      IndexedStorePhase storePhase,
      WalGeneration walGeneration) {
    directory = durableDirectory;
    file = durableFile;
    wal = localWal;
    kernel = tableKernel;
    pages = pageSet;
    database = databaseIncarnation;
    phase = storePhase;
    generation = walGeneration;
    writer = new IndexedCheckpointWriter(directory, pages, database);
    loader = new IndexedCheckpointLoader(directory, file, pages, kernel, database);
  }

  StatusCode flush() {
    if (phase.preparedInsertGroupActive()) {
      return StatusCode.RETRY;
    }
    StatusCode status = writeDirtyPages();
    if (status.isOk()) {
      status = file.truncate((long) pages.highestPageId() * PageCodec.PAGE_BYTES);
    }
    if (status.isOk()) {
      status = file.force(ForceMode.CONTENT_AND_METADATA);
    }
    if (status.isOk()) {
      status = directory.force(new DirectoryOperationResult());
    }
    if (status.isOk()) {
      markAllPagesClean();
    }
    return status;
  }

  private StatusCode writeDirtyPages() {
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      if (!pages.isDirty(pageId)) {
        continue;
      }
      StatusCode status = pages.encodeCurrent(
          pageId,
          database,
          generation,
          pages.recordStart(pageId),
          pages.recordEnd(pageId),
          checksum);
      if (status.isOk()) {
        status = pages.writeCurrent(
            file, pageId, (long) (pageId - 1) * PageCodec.PAGE_BYTES, ioResult);
      }
      if (!status.isOk() || ioResult.bytesTransferred() != PageCodec.PAGE_BYTES) {
        return status.isOk() ? StatusCode.IO_FAILURE : status;
      }
    }
    return StatusCode.OK;
  }

  private void markAllPagesClean() {
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      pages.markClean(pageId);
    }
  }

  StatusCode rebase(WalGeneration nextGeneration) {
    if (phase.operationActive()
        || phase.preparedInsertGroupActive()
        || nextGeneration == null
        || !nextGeneration.isValid()
        || nextGeneration.value() <= generation.value()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = writer.write(nextGeneration);
    if (!status.isOk()) {
      failed = true;
      return status;
    }
    generation = nextGeneration;
    for (int pageId = 1; pageId <= pages.highestPageId(); pageId++) {
      pages.markRebased(pageId);
    }
    return StatusCode.OK;
  }

  StatusCode capture(
      CheckpointState state, long checkpointId, long maximumTransactionId) {
    if (state == null
        || checkpointId <= 0
        || maximumTransactionId <= 0
        || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)
        || pages.hasDirtyPages()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int checkpointRows = kernel.rowCount();
    state.reset();
    StatusCode status = state.set(
        database,
        generation,
        checkpointId,
        wal.currentCommitSequence(),
        maximumTransactionId,
        pages.highestPageId(),
        checkpointRows);
    for (int rowId = 1; status.isOk() && rowId <= checkpointRows; rowId++) {
      status = state.setRowVersion(
          rowId,
          kernel.rowCommitSequence(rowId),
          kernel.previousRowId(rowId),
          kernel.isDeletedRow(rowId));
    }
    return status;
  }

  StatusCode load(CheckpointState checkpoint) {
    return loader.load(checkpoint, generation);
  }

  WalGeneration generation() {
    return generation;
  }

  boolean failed() {
    return failed;
  }
}
