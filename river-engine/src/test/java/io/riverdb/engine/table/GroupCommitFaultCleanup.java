package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.testsupport.fault.FaultingDurableDirectory;
import io.riverdb.wal.local.LocalWal;

final class GroupCommitFaultCleanup {
  ForcedGroupFixture forcedFixture;
  FaultingDurableDirectory directory;
  LocalWal wal;
  IndexedTable table;
  IndexedGroupCommitCoordinator coordinator;

  void close() {
    if (forcedFixture != null) forcedFixture.close();
    if (coordinator != null) coordinator.close();
    StatusCode flush = flushTable();
    StatusCode walClose = wal == null ? StatusCode.CLOSED : wal.close();
    if (directory != null && needsCrash(flush, walClose)) directory.crash();
  }

  private StatusCode flushTable() {
    if (table == null) return StatusCode.CLOSED;
    StatusCode status = table.flush();
    if (status.isOk()) table.close();
    return status;
  }

  private static boolean needsCrash(StatusCode flush, StatusCode walClose) {
    return failedCleanup(flush) || failedCleanup(walClose);
  }

  private static boolean failedCleanup(StatusCode status) {
    return !status.isOk() && status != StatusCode.CLOSED;
  }
}
