package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
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
  private final SqlValueBuffer before = new SqlValueBuffer();

  RelationalDescriptorLockedRows(
      IndexedTransactionSession indexedSession, RelationalDescriptorRowAccess rowAccess) {
    session = indexedSession;
    rows = rowAccess;
    current = new RelationalDescriptorCurrentRow(indexedSession, rowAccess);
  }

  StatusCode lockPoint(
      TableDescriptor table, SqlValueBuffer primaryValues) {
    StatusCode status = rows.reserve(table);
    if (status.isOk()) status = reserveBefore(table);
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
      TableDescriptor table, SqlValueBuffer primaryValues,
      SqlValueBuffer destination, RelationalRowIdentityResult result) {
    if (result != null) result.reset();
    StatusCode status = lockPoint(table, primaryValues);
    if (status.isOk()) status = current.decodeTo(
        table, resolved.logicalRowId(), destination);
    status = finish(status);
    if (status.isOk() && result != null) result.set(resolved.logicalRowId());
    return status;
  }

  StatusCode lockScan(
      RelationalDescriptorScanCursor cursor, SqlValueBuffer destination) {
    TableDescriptor table = cursor.descriptor();
    StatusCode status = rows.reserve(table);
    if (status.isOk()) status = reserveBefore(table);
    if (status.isOk()) status = current.lockScan(cursor, before);
    if (status.isOk()) status = current.decodeTo(
        table, cursor.logicalRowId(), destination);
    return finish(status);
  }

  StatusCode lockLogical(
      TableDescriptor table, long logicalRowId, SqlValueBuffer destination) {
    StatusCode status = rows.reserve(table);
    if (status.isOk()) status = reserveBefore(table);
    if (status.isOk()) status = current.lockPoint(table, logicalRowId, before);
    if (status.isOk()) status = current.decodeTo(table, logicalRowId, destination);
    return finish(status);
  }

  SqlValueBuffer before() { return before; }
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
    return currentStatus.isOk() ? sourceStatus : currentStatus;
  }

  private StatusCode reserveBefore(TableDescriptor table) {
    before.reset();
    return before.reserve(
        table.columnCount(), table.columnCount(),
        table.encodedMaximumRowBytes(), table.encodedMaximumRowBytes());
  }

  private StatusCode finish(StatusCode original) {
    if (original.isOk() || !borrowed()) return original;
    StatusCode released = release();
    return released.isOk() ? original : released;
  }
}
