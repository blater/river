package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.FileIoMode;

/** Acquires and exhaustively cleans up unpublished indexed-store file capabilities. */
final class IndexedOpenFiles {
  private IndexedOpenFiles() { }

  static StatusCode close(DurableFile versions, DurableFile rows, DurableFile pages) {
    StatusCode status = close(versions);
    StatusCode rowsStatus = close(rows);
    StatusCode pagesStatus = close(pages);
    if (status.isOk()) status = rowsStatus;
    return status.isOk() ? pagesStatus : status;
  }

  static StatusCode close(
      StatusCode primary, DurableFile versions, DurableFile rows, DurableFile pages) {
    StatusCode cleanup = close(versions, rows, pages);
    return cleanup.isOk() ? primary : cleanup;
  }

  static StatusCode openAuxiliary(
      DurableDirectory directory,
      DurableFile pages,
      DirectoryOperationResult rows,
      DirectoryOperationResult versions) {
    StatusCode status = reopenOrCreate(
        directory, IndexedTableStore.ROW_DIRECTORY_FILE_NAME, rows);
    if (!status.isOk()) return close(status, null, null, pages);
    status = reopenOrCreate(
        directory, IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, versions);
    return status.isOk() ? StatusCode.OK : close(status, null, rows.file(), pages);
  }

  static StatusCode create(
      DurableDirectory directory,
      DirectoryOperationResult pages,
      DirectoryOperationResult rows,
      DirectoryOperationResult versions) {
    StatusCode status = directory.createFile(
        IndexedTableStore.FILE_NAME, FileIoMode.POSITIONAL, pages);
    if (!status.isOk()) return status;
    status = directory.createFile(
        IndexedTableStore.ROW_DIRECTORY_FILE_NAME, FileIoMode.POSITIONAL, rows);
    if (!status.isOk()) return close(status, null, null, pages.file());
    status = directory.createFile(
        IndexedTableStore.VERSION_DIRECTORY_FILE_NAME, FileIoMode.POSITIONAL, versions);
    return status.isOk()
        ? StatusCode.OK : close(status, null, rows.file(), pages.file());
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

  private static StatusCode close(DurableFile file) {
    return file == null ? StatusCode.OK : file.close();
  }
}
