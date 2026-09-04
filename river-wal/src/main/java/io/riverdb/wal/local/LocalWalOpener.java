package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;

/** Opens, creates, and recovers a local WAL file. */
final class LocalWalOpener {
  private LocalWalOpener() {
  }

  static StatusCode open(
      DurableDirectory directory,
      String fileName,
      DatabaseIncarnation databaseIncarnation,
      WalGeneration walGeneration,
      boolean createWhenMissing,
      boolean requireCreate,
      LocalWalForceCause createForceCause,
      LocalWalOpenResult result) {
    if (directory == null
        || fileName == null
        || fileName.isEmpty()
        || databaseIncarnation == null
        || !databaseIncarnation.isValid()
        || walGeneration == null
        || !walGeneration.isValid()
        || createForceCause == null
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    DirectoryOperationResult operation = new DirectoryOperationResult();
    StatusCode status = requireCreate
        ? directory.createFile(fileName, operation)
        : directory.reopen(fileName, operation);
    boolean created = requireCreate && status.isOk();
    if (!created && status == StatusCode.CONFLICT && createWhenMissing) {
      status = directory.createFile(fileName, operation);
      created = status.isOk();
    }
    if (!status.isOk()) {
      closeIfOpened(operation);
      return status;
    }
    LocalWal wal = new LocalWal(operation.file(), databaseIncarnation, walGeneration, fileName);
    status = created
        ? wal.initializeFileForOpen(directory, createForceCause)
        : wal.recoverValidTailForOpen();
    if (!status.isOk()) {
      wal.closeFileAfterOpen();
      return status;
    }
    result.set(wal);
    return StatusCode.OK;
  }

  private static void closeIfOpened(DirectoryOperationResult operation) {
    if (operation.file() != null) {
      operation.file().close();
    }
  }
}
