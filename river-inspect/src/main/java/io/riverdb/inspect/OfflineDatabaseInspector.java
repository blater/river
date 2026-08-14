package io.riverdb.inspect;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import java.nio.file.Path;

/** Read-only, quiescent inspection of River control, WAL, and page files. */
public final class OfflineDatabaseInspector {
  private static final int MAXIMUM_DIRECTORY_ENTRIES = 256;

  private final DirectoryListResult entries =
      new DirectoryListResult(MAXIMUM_DIRECTORY_ENTRIES);
  private final OfflineInspectionFile file = new OfflineInspectionFile();
  private final OfflineControlInspector control =
      new OfflineControlInspector(file);
  private final OfflineWalInspector wal = new OfflineWalInspector(file);
  private final OfflinePageInspector pages = new OfflinePageInspector(file);

  public StatusCode inspect(Path directoryPath, DatabaseInspectionResult result) {
    if (directoryPath == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    NioDirectoryOpenResult opened = new NioDirectoryOpenResult();
    StatusCode status = NioDurableDirectory.openExisting(
        directoryPath,
        new FatalStateFence(),
        new NioIoCounters(),
        2,
        opened);
    if (!status.isOk()) {
      return status;
    }
    NioDurableDirectory directory = opened.directory();
    status = inspectOpenDirectory(directory, result);
    StatusCode close = directory.close();
    if (status.isOk()) {
      status = close;
    }
    if (status.isOk()) {
      result.complete();
    } else {
      result.reset();
    }
    return status;
  }

  private StatusCode inspectOpenDirectory(
      NioDurableDirectory directory, DatabaseInspectionResult result) {
    StatusCode status = control.inspect(directory, result);
    if (status.isOk()) {
      entries.reset();
      status = directory.list(entries);
    }
    for (int index = 0; status.isOk() && index < entries.size(); index++) {
      status = inspectEntry(directory, result, index);
    }
    return status;
  }

  private StatusCode inspectEntry(
      NioDurableDirectory directory,
      DatabaseInspectionResult result,
      int index) {
    if (entries.type(index) != DirectoryEntryType.FILE) {
      result.addUnrecognizedEntry();
      return StatusCode.OK;
    }
    String name = entries.name(index);
    if (OfflineControlInspector.FILE_NAME.equals(name)) {
      return StatusCode.OK;
    }
    if (OfflinePhysicalFileNames.matches(
        name, OfflinePhysicalFileNames.WAL_FILE)) {
      return wal.inspect(directory, name, result);
    }
    if (OfflinePhysicalFileNames.matches(
        name, OfflinePhysicalFileNames.PAGE_FILE)) {
      return pages.inspect(directory, name, result);
    }
    result.addUnrecognizedEntry();
    return StatusCode.OK;
  }
}
