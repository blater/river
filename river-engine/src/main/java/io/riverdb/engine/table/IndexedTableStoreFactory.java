package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.runtime.DatabaseProviderLease;
import io.riverdb.engine.runtime.DatabaseStoreLease;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.platform.file.FileIoMode;

/** Owns indexed-store file acquisition, checkpoint bootstrap, and recovery admission. */
final class IndexedTableStoreFactory {
  private IndexedTableStoreFactory() {}

  static StatusCode create(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      DatabaseProviderLease providerLease,
      IndexedTableStoreOpenResult result) {
    if (providerLease == null || !providerLease.active()
        || !validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    DatabaseStoreLease storeLease = new DatabaseStoreLease();
    StatusCode claim = providerLease.claimStore(
        database.high(), database.low(), generation.value(), storeLease);
    if (!claim.isOk()) return claim;
    StatusCode status = createClaimed(
        directory, wal, database, generation, providerLease, storeLease, result);
    return finishClaim(providerLease, storeLease, status);
  }

  private static StatusCode createClaimed(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease,
      IndexedTableStoreOpenResult result) {
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
    StatusCode status = directory.createFile(IndexedTableStore.FILE_NAME, FileIoMode.POSITIONAL, operation);
    if (!status.isOk()) {
      return status;
    }
    status = directory.createFile(IndexedTableStore.ROW_DIRECTORY_FILE_NAME, FileIoMode.POSITIONAL, rows);
    if (!status.isOk()) {
      return IndexedOpenFiles.close(status, null, null, operation.file());
    }
    status = directory.createFile(IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, FileIoMode.POSITIONAL, versions);
    if (!status.isOk()) {
      return IndexedOpenFiles.close(status, null, rows.file(), operation.file());
    }
    return IndexedTableStoreConstruction.construct(
        directory, operation, rows, versions, wal, database, generation, result,
        providerLease, storeLease);
  }

  static StatusCode open(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      DatabaseProviderLease providerLease,
      boolean createWhenMissing,
      IndexedTableStoreOpenResult result) {
    if (providerLease == null || !providerLease.active()
        || !validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    DatabaseStoreLease storeLease = new DatabaseStoreLease();
    StatusCode claim = providerLease.claimStore(
        database.high(), database.low(), generation.value(), storeLease);
    if (!claim.isOk()) return claim;
    StatusCode status = openClaimed(
        directory, wal, database, generation, providerLease, storeLease,
        createWhenMissing, result);
    return finishClaim(providerLease, storeLease, status);
  }

  private static StatusCode openClaimed(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease,
      boolean createWhenMissing,
      IndexedTableStoreOpenResult result) {
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
    StatusCode status = directory.reopen(IndexedTableStore.FILE_NAME, FileIoMode.POSITIONAL, operation);
    if (status == StatusCode.CONFLICT && createWhenMissing) {
      status = directory.createFile(IndexedTableStore.FILE_NAME, FileIoMode.POSITIONAL, operation);
    }
    if (!status.isOk()) {
      return status;
    }
    status = reopenOrCreate(directory, IndexedTableStore.ROW_DIRECTORY_FILE_NAME, rows);
    if (!status.isOk()) {
      return IndexedOpenFiles.close(status, null, null, operation.file());
    }
    status = reopenOrCreate(directory, IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, versions);
    if (!status.isOk()) {
      return IndexedOpenFiles.close(status, null, rows.file(), operation.file());
    }
    return IndexedTableStoreConstruction.open(
        directory, operation, rows, versions, wal, database, generation,
        providerLease, storeLease, result);
  }

  static StatusCode openCheckpoint(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      CheckpointState checkpoint,
      DatabaseProviderLease providerLease,
      IndexedTableStoreOpenResult result) {
    if (providerLease == null || !providerLease.active()
        || !validCheckpoint(checkpoint, database)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    WalGeneration generation = checkpoint.walGeneration();
    if (!validInput(directory, wal, database, generation, result)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    DatabaseStoreLease storeLease = new DatabaseStoreLease();
    StatusCode claim = providerLease.claimStore(
        database.high(), database.low(), generation.value(), storeLease);
    if (!claim.isOk()) return claim;
    StatusCode status = openCheckpointClaimed(
        directory, wal, database, checkpoint, providerLease, storeLease, result);
    return finishClaim(providerLease, storeLease, status);
  }

  private static StatusCode openCheckpointClaimed(
      DurableDirectory directory,
      LocalWal wal,
      DatabaseIncarnation database,
      CheckpointState checkpoint,
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease,
      IndexedTableStoreOpenResult result) {
    WalGeneration generation = checkpoint.walGeneration();
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
    StatusCode status = directory.reopen(IndexedTableStore.FILE_NAME, FileIoMode.POSITIONAL, operation);
    if (!status.isOk()) {
      return status == StatusCode.CONFLICT ? StatusCode.CORRUPTION : status;
    }
    status = reopenOrCreate(directory, IndexedTableStore.ROW_DIRECTORY_FILE_NAME, rows);
    if (!status.isOk()) {
      return IndexedOpenFiles.close(status, null, null, operation.file());
    }
    status = reopenOrCreate(directory, IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, versions);
    if (!status.isOk()) {
      return IndexedOpenFiles.close(status, null, rows.file(), operation.file());
    }
    return IndexedTableStoreConstruction.openCheckpoint(
        directory, operation, rows, versions, wal, database, generation,
        checkpoint, providerLease, storeLease, result);
  }

  private static StatusCode finishClaim(
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease,
      StatusCode status) {
    if (status.isOk() || !providerLease.storeClaimed()) return status;
    StatusCode release = providerLease.releaseStore(storeLease);
    return release.isOk() ? status : release;
  }

  private static boolean validCheckpoint(
      CheckpointState checkpoint, DatabaseIncarnation database) {
    return checkpoint != null
        && checkpoint.isAvailable()
        && checkpoint.database().equals(database);
  }

  private static StatusCode reopenOrCreate(
      DurableDirectory directory,
      String fileName,
      DirectoryOperationResult result) {
    StatusCode status = directory.reopen(fileName, FileIoMode.POSITIONAL, result);
    if (status == StatusCode.CONFLICT) {
      status = directory.createFile(fileName, FileIoMode.POSITIONAL, result);
    }
    return status;
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
