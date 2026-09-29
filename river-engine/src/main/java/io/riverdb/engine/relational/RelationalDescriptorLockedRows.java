package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.lock.LockMode;

/** Owns the canonical before-image bound to one borrowed current-row capability. */
final class RelationalDescriptorLockedRows {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorCurrentRow current;
  private final RelationalDescriptorPrimaryAccess primary;
  private final RelationalRowIdentityResult resolved = new RelationalRowIdentityResult();
  private final StoredTableRowView before = new StoredTableRowView();

  RelationalDescriptorLockedRows(
      IndexedTransactionSession indexedSession,
      RelationalDescriptorPointViews pointViews) {
    session = indexedSession;
    primary = new RelationalDescriptorPrimaryAccess(pointViews);
    current = new RelationalDescriptorCurrentRow(indexedSession, primary);
  }

  StatusCode lockPoint(
      TableDescriptor table, SqlValueAccess primaryValues) {
    StatusCode status = before.reset();
    boolean serializable = session.transaction().isolationLevel() == IsolationLevel.SERIALIZABLE;
    if (status.isOk()) status = serializable
        ? primary.resolveSource(
            session, table, primaryValues, LockMode.EXCLUSIVE, resolved)
        : primary.resolve(session, table, primaryValues, resolved);
    if (status.isOk()) status = current.lockPoint(
        table, primaryValues, resolved.logicalRowId(), before);
    return finish(status);
  }

  StatusCode lockPoint(
      TableDescriptor table, SqlValueAccess primaryValues,
      StoredTableRowView destination, RelationalRowIdentityResult result) {
    if (result != null) result.reset();
    StatusCode status = lockPoint(table, primaryValues);
    if (status.isOk()) status = destination.borrowFrom(before);
    status = finish(status);
    if (status.isOk() && result != null) result.set(resolved.logicalRowId());
    return status;
  }

  StatusCode lockScan(
      RelationalDescriptorScanCursor cursor, StoredTableRowView destination) {
    StatusCode status = before.reset();
    if (status.isOk()) status = current.lockScan(cursor, before);
    if (status.isOk()) status = destination.borrowFrom(before);
    return finish(status);
  }

  StatusCode lockLogical(
      TableDescriptor table, long logicalRowId, StoredTableRowView destination) {
    StatusCode status = before.reset();
    if (status.isOk()) status = current.lockLogical(table, logicalRowId, before);
    if (status.isOk()) status = destination.borrowFrom(before);
    return finish(status);
  }

  SqlValueAccess before() { return before; }
  long logicalRowId() { return current.logicalRowId(); }
  boolean borrowed() { return current.borrowed() || session.tupleSourceBorrowed(); }

  StatusCode retain() {
    StatusCode status = session.tupleSourceBorrowed()
        ? session.retainTupleSource() : StatusCode.OK;
    StatusCode currentStatus = current.borrowed() ? current.retain() : StatusCode.OK;
    StatusCode viewStatus = before.reset();
    if (!status.isOk()) return status;
    return currentStatus.isOk() ? viewStatus : currentStatus;
  }

  StatusCode release() {
    StatusCode currentStatus = current.borrowed() ? current.release() : StatusCode.OK;
    StatusCode sourceStatus = session.tupleSourceBorrowed()
        ? session.releaseTupleSource() : StatusCode.OK;
    StatusCode rowStatus = before.reset();
    if (!currentStatus.isOk()) return currentStatus;
    return sourceStatus.isOk() ? rowStatus : sourceStatus;
  }

  private StatusCode finish(StatusCode original) {
    if (original.isOk() || !borrowed()) return original;
    StatusCode released = release();
    return released.isOk() ? original : released;
  }
}
