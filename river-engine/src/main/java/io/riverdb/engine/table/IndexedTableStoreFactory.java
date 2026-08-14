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
    if (!validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.createFile(IndexedTableStore.FILE_NAME, operation);
    if (!status.isOk()) {
      return status;
    }
    result.set(new IndexedTableStore(
        directory, operation.file(), wal, database, generation));
    return StatusCode.OK;
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
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.reopen(IndexedTableStore.FILE_NAME, operation);
    if (status == StatusCode.CONFLICT && createWhenMissing) {
      status = directory.createFile(IndexedTableStore.FILE_NAME, operation);
    }
    if (!status.isOk()) {
      return status;
    }
    IndexedTableStore store = new IndexedTableStore(
        directory, operation.file(), wal, database, generation);
    status = store.recoverFromWal();
    if (status.isOk()) {
      status = store.flush();
    }
    return finish(store, result, status);
  }

  static StatusCode openCheckpoint(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      CheckpointState checkpoint,
      IndexedTableStoreOpenResult result) {
    if (checkpoint == null
        || !checkpoint.isAvailable()
        || !checkpoint.database().equals(database)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    WalGeneration generation = checkpoint.walGeneration();
    if (!validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = directory.reopen(IndexedTableStore.FILE_NAME, operation);
    if (!status.isOk()) {
      return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    }
    IndexedTableStore store = new IndexedTableStore(
        directory, operation.file(), wal, database, generation);
    status = store.loadCheckpoint(checkpoint);
    if (status.isOk()) {
      status = store.recoverFromWal();
    }
    if (status.isOk()) {
      status = store.flush();
    }
    return finish(store, result, status);
  }

  private static StatusCode finish(
      IndexedTableStore store,
      IndexedTableStoreOpenResult result,
      StatusCode status) {
    if (!status.isOk()) {
      store.closeOpenFile();
      return status;
    }
    result.set(store);
    return StatusCode.OK;
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
