package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.wal.local.LocalWal;

/** Owns indexed-store file acquisition, checkpoint bootstrap, and recovery admission. */
final class IndexedTableStoreFactory {
  private IndexedTableStoreFactory() {}

  static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      IndexedTableStoreOpenResult result) {
    return create(
        directory, wal, database, generation, IndexedPageCacheConfig.DEFAULT, result);
  }

  static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      IndexedPageCacheConfig pageCacheConfig,
      IndexedTableStoreOpenResult result) {
    if (pageCacheConfig == null
        || !validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation;
    DirectoryOperationResult rows;
    DirectoryOperationResult versions;
    try {
      operation = new DirectoryOperationResult();
      rows = new DirectoryOperationResult();
      versions = new DirectoryOperationResult();
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = directory.createFile(IndexedTableStore.FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    status = directory.createFile(IndexedTableStore.ROW_DIRECTORY_FILE_NAME, rows);
    if (!status.isOk()) {
      return cleanup(status, null, operation.file());
    }
    status = directory.createFile(IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, versions);
    if (!status.isOk()) {
      return cleanup(status, rows.file(), operation.file());
    }
    return IndexedTableStoreConstruction.construct(
        directory, operation, rows, versions, wal, database, generation, result,
        pageCacheConfig);
  }

  static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      boolean createWhenMissing,
      IndexedTableStoreOpenResult result) {
    if (!validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation;
    DirectoryOperationResult rows;
    DirectoryOperationResult versions;
    try {
      operation = new DirectoryOperationResult();
      rows = new DirectoryOperationResult();
      versions = new DirectoryOperationResult();
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = directory.reopen(IndexedTableStore.FILE_NAME, operation);
    if (status == StatusCode.CONFLICT && createWhenMissing) {
      status = directory.createFile(IndexedTableStore.FILE_NAME, operation);
    }
    if (!status.isOk()) {
      return status;
    }
    status = openRowDirectory(directory, rows);
    if (!status.isOk()) {
      return cleanup(status, null, operation.file());
    }
    status = openVersionDirectory(directory, versions);
    if (!status.isOk()) {
      return cleanup(status, rows.file(), operation.file());
    }
    return IndexedTableStoreConstruction.open(
        directory, operation, rows, versions, wal, database, generation, result);
  }

  static StatusCode openCheckpoint(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      CheckpointState checkpoint,
      IndexedTableStoreOpenResult result) {
    if (!validCheckpoint(checkpoint, database)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    WalGeneration generation = checkpoint.walGeneration();
    if (!validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation;
    DirectoryOperationResult rows;
    DirectoryOperationResult versions;
    try {
      operation = new DirectoryOperationResult();
      rows = new DirectoryOperationResult();
      versions = new DirectoryOperationResult();
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = directory.reopen(IndexedTableStore.FILE_NAME, operation);
    if (!status.isOk()) {
      return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    }
    status = openRowDirectory(directory, rows);
    if (!status.isOk()) {
      return cleanup(status, null, operation.file());
    }
    status = openVersionDirectory(directory, versions);
    if (!status.isOk()) {
      return cleanup(status, rows.file(), operation.file());
    }
    return IndexedTableStoreConstruction.openCheckpoint(
        directory, operation, rows, versions, wal, database, generation, checkpoint, result);
  }

  private static boolean validCheckpoint(
      CheckpointState checkpoint, DatabaseIncarnation database) {
    return checkpoint != null
        && checkpoint.isAvailable()
        && checkpoint.database().equals(database);
  }

  private static StatusCode openRowDirectory(
      DurableDirectory directory,
      DirectoryOperationResult result) {
    StatusCode status = directory.reopen(
        IndexedTableStore.ROW_DIRECTORY_FILE_NAME, result);
    if (status == StatusCode.CONFLICT) {
      status = directory.createFile(IndexedTableStore.ROW_DIRECTORY_FILE_NAME, result);
    }
    return status;
  }

  private static StatusCode openVersionDirectory(
      DurableDirectory directory,
      DirectoryOperationResult result) {
    StatusCode status = directory.reopen(
        IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, result);
    if (status == StatusCode.CONFLICT) {
      status = directory.createFile(IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, result);
    }
    return status;
  }

  private static StatusCode cleanup(
      StatusCode operation,
      io.riverdb.platform.file.DurableFile rows,
      io.riverdb.platform.file.DurableFile pages) {
    StatusCode cleanup = IndexedOpenFiles.close(null, rows, pages);
    return cleanup.isOk() ? operation : cleanup;
  }

  private static boolean validInput(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      IndexedTableStoreOpenResult result) {
    return directory != null
        && wal != null
        && database != null
        && database.isValid()
        && generation != null
        && generation.isValid()
        && result != null
        && database.equals(wal.databaseIncarnation())
        && generation.equals(wal.walGeneration());
  }
}
