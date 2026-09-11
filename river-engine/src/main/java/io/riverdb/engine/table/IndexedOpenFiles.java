package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;

/** Exhaustive cleanup of unpublished indexed-store file capabilities. */
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

  private static StatusCode close(DurableFile file) {
    return file == null ? StatusCode.OK : file.close();
  }
}
