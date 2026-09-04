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

/** Translates unpublished indexed-store allocation failure and closes its acquired files. */
final class IndexedTableStoreConstruction {
  private IndexedTableStoreConstruction() {}

  static StatusCode construct(
      DurableDirectory directory,
      DirectoryOperationResult pages,
      DirectoryOperationResult rows,
      DirectoryOperationResult versions,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      IndexedTableStoreOpenResult result,
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease) {
    return construct(
        directory, pages, rows, versions, wal, database, generation, result,
        providerLease, storeLease, IndexedTableStoreAllocator.SYSTEM);
  }

  static StatusCode construct(
      DurableDirectory directory,
      DirectoryOperationResult pages,
      DirectoryOperationResult rows,
      DirectoryOperationResult versions,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      IndexedTableStoreOpenResult result,
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease,
      IndexedTableStoreAllocator allocator) {
    try {
      IndexedTableStore store = allocator.allocate(
          directory, pages.file(), rows.file(), versions.file(), wal, database, generation,
          providerLease, storeLease);
      if (store == null) {
        StatusCode cleanup = IndexedOpenFiles.close(
            versions.file(), rows.file(), pages.file());
        return cleanup.isOk() ? StatusCode.INVARIANT_BROKEN : cleanup;
      }
      result.set(store);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      StatusCode cleanup = IndexedOpenFiles.close(
          versions.file(), rows.file(), pages.file());
      return cleanup.isOk() ? StatusCode.RESOURCE_EXHAUSTED : cleanup;
    }
  }

  static StatusCode open(
      DurableDirectory directory,
      DirectoryOperationResult pages,
      DirectoryOperationResult rows,
      DirectoryOperationResult versions,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease,
      IndexedTableStoreOpenResult result) {
    StatusCode status = construct(
        directory, pages, rows, versions, wal, database, generation, result,
        providerLease, storeLease);
    IndexedTableStore store = result.store();
    try {
      if (status.isOk()) status = store.recoverFromWal();
      if (status.isOk()) status = store.flush();
    } catch (OutOfMemoryError error) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return finish(store, result, status);
  }

  static StatusCode openCheckpoint(
      DurableDirectory directory,
      DirectoryOperationResult pages,
      DirectoryOperationResult rows,
      DirectoryOperationResult versions,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      CheckpointState checkpoint,
      DatabaseProviderLease providerLease,
      DatabaseStoreLease storeLease,
      IndexedTableStoreOpenResult result) {
    StatusCode status = construct(
        directory, pages, rows, versions, wal, database, generation, result,
        providerLease, storeLease);
    IndexedTableStore store = result.store();
    try {
      if (status.isOk()) status = store.loadCheckpoint(checkpoint);
      if (status.isOk()) status = store.recoverFromWal();
      if (status.isOk()) status = store.flush();
    } catch (OutOfMemoryError error) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return finish(store, result, status);
  }

  private static StatusCode finish(
      IndexedTableStore store,
      IndexedTableStoreOpenResult result,
      StatusCode status) {
    if (status.isOk()) return StatusCode.OK;
    if (store != null) store.closeOpenFile();
    result.reset();
    return status;
  }
}
