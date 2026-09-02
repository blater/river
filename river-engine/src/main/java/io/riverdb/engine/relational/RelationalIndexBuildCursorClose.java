package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Failure-preserving close for the reusable index-build cursor. */
final class RelationalIndexBuildCursorClose {
  private RelationalIndexBuildCursorClose() {
  }

  static StatusCode close(
      RelationalSession session, RelationalScanCursor cursor, StatusCode status) {
    if (!cursor.isActive()) return status;
    StatusCode close = session.closeScan(cursor);
    return status.isOk() ? close : status;
  }
}
