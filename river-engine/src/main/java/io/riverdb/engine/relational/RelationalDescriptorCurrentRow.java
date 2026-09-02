package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedLockedRow;
import io.riverdb.engine.table.IndexedRowCandidate;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Owns one reusable descriptor-row candidate and its lock-protected current successor. */
final class RelationalDescriptorCurrentRow {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorRowAccess rows;
  private final IndexedRowCandidate candidate = new IndexedRowCandidate();
  private final IndexedLockedRow locked = new IndexedLockedRow();
  private long logicalRowId;

  RelationalDescriptorCurrentRow(
      IndexedTransactionSession indexedSession, RelationalDescriptorRowAccess rowAccess) {
    session = indexedSession;
    rows = rowAccess;
  }

  StatusCode lockPoint(
      TableDescriptor table, long rowId, SqlValueBuffer destination) {
    StatusCode status = reset();
    if (!status.isOk()) return status;
    status = session.fetchCandidateByKey(
        RelationalDescriptorKeyspace.baseRows(table.tableId()), rowId, candidate);
    if (status.isOk()) status = session.lockCurrent(candidate, locked);
    return finish(table, rowId, destination, status);
  }

  StatusCode lockPointCurrent(
      TableDescriptor table, long rowId, SqlValueBuffer destination) {
    StatusCode status = reset();
    if (!status.isOk()) return status;
    status = session.lockCurrentKeyCurrent(
        RelationalDescriptorKeyspace.baseRows(table.tableId()), rowId, locked);
    return finish(table, rowId, destination, status);
  }

  StatusCode lockScan(
      RelationalDescriptorScanCursor cursor, SqlValueBuffer destination) {
    StatusCode status = reset();
    if (!status.isOk()) return status;
    TableDescriptor table = cursor.descriptor();
    long rowId = cursor.logicalRowId();
    if (cursor.isTuplePhysical()) {
      status = session.fetchCandidateByKey(
          RelationalDescriptorKeyspace.baseRows(table.tableId()), rowId, candidate);
      if (status.isOk()) status = session.lockCurrent(candidate, locked);
    } else {
      status = session.lockCurrent(cursor.row(), locked);
    }
    status = finish(table, rowId, destination, status);
    if (status.isOk() && cursor.isTuplePhysical()) {
      status = cursor.tupleBounds().recheck(destination);
      if (status.isOk() && !cursor.tupleBounds().matches()) status = StatusCode.CONFLICT;
    }
    if (!status.isOk() && locked.isAvailable()) {
      StatusCode released = release();
      if (!released.isOk()) status = released;
    }
    return status;
  }

  IndexedLockedRow locked() { return locked; }

  long logicalRowId() { return logicalRowId; }

  boolean borrowed() { return locked.isAvailable(); }

  StatusCode decodeTo(
      TableDescriptor table, long rowId, SqlValueBuffer destination) {
    return logicalRowId == rowId && locked.isAvailable()
        ? rows.decode(table, rowId, locked.row(), destination)
        : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  StatusCode retain() {
    StatusCode status = session.retainLocked(locked);
    if (status.isOk()) logicalRowId = 0;
    return status;
  }

  StatusCode release() {
    StatusCode status = session.releaseLocked(locked);
    if (status.isOk()) logicalRowId = 0;
    return status;
  }

  StatusCode reset() {
    candidate.reset();
    StatusCode status = locked.reset();
    logicalRowId = 0;
    return status;
  }

  private StatusCode finish(
      TableDescriptor table, long rowId, SqlValueBuffer destination, StatusCode status) {
    if (!status.isOk()) return status;
    status = rows.decode(table, rowId, locked.row(), destination);
    if (status.isOk()) logicalRowId = rowId;
    if (status.isOk()) return status;
    StatusCode released = session.releaseLocked(locked);
    return released.isOk() ? status : released;
  }
}
