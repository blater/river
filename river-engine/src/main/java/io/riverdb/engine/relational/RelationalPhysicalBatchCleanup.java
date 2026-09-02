package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;

/** Executes one bounded physical-row cleanup batch. */
final class RelationalPhysicalBatchCleanup {
  private RelationalPhysicalBatchCleanup() { }

  static StatusCode run(RelationalPhysicalCleanup cleanup, RelationalSession session,
      int tableId, TransactionOutcome outcome) {
    cleanup.batchComplete = false;
    StatusCode status = session.begin(io.riverdb.tx.api.IsolationLevel.REPEATABLE_READ);
    if (status.isOk()) {
      long dataSpace = RelationalKey.dataSpace(tableId);
      status = session.indexedSession().beginScan(dataSpace, Long.MIN_VALUE,
          RelationalKey.auxiliarySpace(tableId) + 1, Long.MIN_VALUE, cleanup.scanCursor);
    }
    int count = 0;
    if (status.isOk()) {
      count = collect(cleanup, session);
      status = cleanup.collectStatus;
    }
    if (cleanup.scanCursor.isActive()) {
      StatusCode close = session.indexedSession().closeScan(cleanup.scanCursor);
      if (status.isOk()) status = close;
    }
    cleanup.scanCursor.reset();
    for (int index = 0; status.isOk() && index < count; index++) {
      status = session.indexedSession().delete(cleanup.rowSpaces[index], cleanup.rowKeys[index]);
      cleanup.rowSpaces[index] = 0;
      cleanup.rowKeys[index] = 0;
    }
    if (status.isOk()) status = session.commitBuildPhase(outcome);
    else if (session.indexedSession().transaction().state() == TransactionState.ACTIVE) {
      StatusCode abort = session.abortBuildPhase(outcome);
      if (!abort.isOk()) status = abort;
    }
    cleanup.batchComplete = status.isOk() && cleanup.scanExhausted;
    return status;
  }

  private static int collect(RelationalPhysicalCleanup cleanup, RelationalSession session) {
    int count = 0;
    cleanup.collectStatus = StatusCode.OK;
    cleanup.scanExhausted = false;
    while (cleanup.collectStatus.isOk() && count < cleanup.rowKeys.length) {
      cleanup.collectStatus = session.indexedSession().nextScan(cleanup.scanCursor, cleanup.scanRow);
      if (cleanup.collectStatus == StatusCode.CONFLICT) {
        cleanup.collectStatus = StatusCode.OK;
        cleanup.scanExhausted = true;
        break;
      }
      if (cleanup.collectStatus.isOk()) {
        cleanup.rowSpaces[count] = cleanup.scanRow.keySpace();
        cleanup.rowKeys[count++] = cleanup.scanRow.key();
      }
    }
    return count;
  }
}
