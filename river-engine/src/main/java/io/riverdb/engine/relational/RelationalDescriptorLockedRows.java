package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedLockedRow;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.lock.LockMode;

/** Owns the canonical before-image bound to one borrowed current-row capability. */
final class RelationalDescriptorLockedRows {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorRowAccess rows;
  private final RelationalDescriptorCurrentRow current;
  private final RelationalDescriptorPrimaryAccess primary =
      new RelationalDescriptorPrimaryAccess();
  private final RelationalRowIdentityResult resolved = new RelationalRowIdentityResult();
  private final StoredTableRowView before = new StoredTableRowView();

  RelationalDescriptorLockedRows(
      IndexedTransactionSession indexedSession, RelationalDescriptorRowAccess rowAccess) {
    session = indexedSession;
    rows = rowAccess;
    current = new RelationalDescriptorCurrentRow(indexedSession);
  }

  StatusCode lockPoint(
      TableDescriptor table, SqlValueAccess primaryValues) {
    before.reset();
    StatusCode status = rows.reserve(table);
    boolean serializable = session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE;
    if (status.isOk()) status = serializable
        ? primary.resolveSource(
            session, table, primaryValues, LockMode.EXCLUSIVE, resolved)
        : primary.resolve(session, table, primaryValues, resolved);
    if (status.isOk()) status = serializable
        ? current.lockPointCurrent(table, resolved.logicalRowId(), before)
        : current.lockPoint(table, resolved.logicalRowId(), before);
    if (status.isOk()) status = primary.validateResolved(table, before);
    return finish(status);
  }

  StatusCode lockPoint(
      TableDescriptor table, SqlValueAccess primaryValues,
      StoredTableRowView destination, RelationalRowIdentityResult result) {
    if (result != null) result.reset();
    StatusCode status = lockPoint(table, primaryValues);
    if (status.isOk()) destination.borrowFrom(before);
    status = finish(status);
    if (status.isOk() && result != null) result.set(resolved.logicalRowId());
    return status;
  }

  StatusCode lockScan(
      RelationalDescriptorScanCursor cursor, StoredTableRowView destination) {
    TableDescriptor table = cursor.descriptor();
    StatusCode status = rows.reserve(table);
    if (status.isOk()) before.reset();
    if (status.isOk()) status = current.lockScan(cursor, before);
    if (status.isOk()) destination.borrowFrom(before);
    return finish(status);
  }

  StatusCode lockLogical(
      TableDescriptor table, long logicalRowId, StoredTableRowView destination) {
    StatusCode status = rows.reserve(table);
    if (status.isOk()) before.reset();
    if (status.isOk()) status = current.lockPoint(table, logicalRowId, before);
    if (status.isOk()) destination.borrowFrom(before);
    return finish(status);
  }

  SqlValueAccess before() { return before; }
  IndexedLockedRow locked() { return current.locked(); }
  long logicalRowId() { return current.logicalRowId(); }
  boolean borrowed() { return current.borrowed() || session.tupleSourceBorrowed(); }

  StatusCode retain() {
    StatusCode status = session.tupleSourceBorrowed()
        ? session.retainTupleSource() : StatusCode.OK;
    StatusCode currentStatus = current.borrowed() ? current.retain() : StatusCode.OK;
    return status.isOk() ? currentStatus : status;
  }

  StatusCode release() {
    StatusCode currentStatus = current.borrowed() ? current.release() : StatusCode.OK;
    StatusCode sourceStatus = session.tupleSourceBorrowed()
        ? session.releaseTupleSource() : StatusCode.OK;
    if (currentStatus.isOk() && sourceStatus.isOk()) before.reset();
    return currentStatus.isOk() ? sourceStatus : currentStatus;
  }

  private StatusCode finish(StatusCode original) {
    if (original.isOk() || !borrowed()) return original;
    StatusCode released = release();
    return released.isOk() ? original : released;
  }
}
