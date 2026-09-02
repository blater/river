package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Centralizes the store's durable-health gate before every operation. */
final class IndexedTableAdmission {
  private IndexedTableAdmission() {
  }

  static StatusCode status(
      StatusCode rowStatus,
      StatusCode versionStatus,
      boolean failed,
      boolean pageFailed,
      boolean checkpointFailed,
      boolean closed) {
    if (!rowStatus.isOk()) return rowStatus;
    if (!versionStatus.isOk()) return versionStatus;
    if (failed || pageFailed || checkpointFailed) {
      return StatusCode.FENCED;
    }
    return closed ? StatusCode.CLOSED : StatusCode.OK;
  }
}
