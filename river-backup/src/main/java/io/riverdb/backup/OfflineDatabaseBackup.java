package io.riverdb.backup;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.file.Path;

/** Manifest-last backup and no-clobber restore for a quiescent database. */
public final class OfflineDatabaseBackup {
  public static final String MANIFEST_FILE_NAME =
      OfflineBackupCatalog.MANIFEST_FILE_NAME;

  private final DirectoryListResult targetEntries = new DirectoryListResult(1);
  private final DirectoryOperationResult targetOperation =
      new DirectoryOperationResult();
  private final OfflineBackupCatalog catalog = new OfflineBackupCatalog();
  private final OfflineBackupFileCopier files = new OfflineBackupFileCopier();

  /** Copies a closed database directory into an existing empty backup directory. */
  public StatusCode create(Path sourcePath, Path backupPath, BackupResult result) {
    return transfer(sourcePath, backupPath, result, false);
  }

  /** Restores a complete backup into an existing empty database directory. */
  public StatusCode restore(Path backupPath, Path destinationPath, BackupResult result) {
    return transfer(backupPath, destinationPath, result, true);
  }

  private StatusCode transfer(
      Path sourcePath,
      Path targetPath,
      BackupResult result,
      boolean restoring) {
    if (sourcePath == null || targetPath == null || result == null
        || !catalog.isAvailable() || !files.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    catalog.reset();
    NioDirectoryOpenResult sourceResult = new NioDirectoryOpenResult();
    NioDirectoryOpenResult targetResult = new NioDirectoryOpenResult();
    StatusCode status = openDirectory(sourcePath, sourceResult);
    if (status.isOk()) {
      status = openDirectory(targetPath, targetResult);
    }
    NioDurableDirectory source = sourceResult.directory();
    NioDurableDirectory target = targetResult.directory();
    if (status.isOk()) {
      status = transferOpenDirectories(source, target, restoring);
    }
    if (status.isOk()) {
      result.complete(
          catalog.database(),
          catalog.walGeneration(),
          catalog.fileCount(),
          catalog.totalBytes());
    }
    status = closeDirectories(source, target, status);
    if (!status.isOk()) {
      result.reset();
    }
    return status;
  }

  private StatusCode transferOpenDirectories(
      NioDurableDirectory source,
      NioDurableDirectory target,
      boolean restoring) {
    if (source.root().equals(target.root())) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = requireEmpty(target);
    if (status.isOk()) {
      status = restoring
          ? catalog.readManifest(source)
          : catalog.collectDatabaseFiles(source);
    }
    if (status.isOk() && restoring) {
      status = catalog.validateBackupEntries(source);
    }
    if (status.isOk()) {
      status = copyFiles(source, target, restoring);
    }
    if (status.isOk()) {
      status = target.force(targetOperation);
    }
    if (status.isOk() && !restoring) {
      status = catalog.writeManifest(target);
    }
    return status;
  }

  private StatusCode copyFiles(
      NioDurableDirectory source,
      NioDurableDirectory target,
      boolean restoring) {
    for (int index = 0; index < catalog.fileCount(); index++) {
      StatusCode status = files.copy(source, target, catalog, index, restoring);
      if (!status.isOk()) {
        return status;
      }
    }
    return StatusCode.OK;
  }

  private StatusCode requireEmpty(NioDurableDirectory directory) {
    targetEntries.reset();
    StatusCode status = directory.list(targetEntries);
    return status.isOk() && targetEntries.size() != 0
        ? StatusCode.CONFLICT : status;
  }

  private static StatusCode openDirectory(
      Path path, NioDirectoryOpenResult result) {
    return NioDurableDirectory.openExisting(
        path, new FatalStateFence(), new NioIoCounters(), 4, result);
  }

  private static StatusCode closeDirectories(
      NioDurableDirectory source,
      NioDurableDirectory target,
      StatusCode status) {
    StatusCode sourceClose = source == null ? StatusCode.OK : source.close();
    StatusCode targetClose = target == null ? StatusCode.OK : target.close();
    if (status.isOk()) {
      status = sourceClose;
    }
    return status.isOk() ? targetClose : status;
  }
}
