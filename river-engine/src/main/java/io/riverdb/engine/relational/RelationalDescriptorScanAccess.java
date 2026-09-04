package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.lock.LockMode;

/** Owns bounded allocation-free descriptor scans for its transaction session. */
final class RelationalDescriptorScanAccess {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorRowAccess rowAccess;
  private final RelationalDescriptorScanRegistry active =
      new RelationalDescriptorScanRegistry();

  RelationalDescriptorScanAccess(
      IndexedTransactionSession indexedSession, RelationalDescriptorRowAccess rows) {
    session = indexedSession;
    rowAccess = rows;
  }

  StatusCode begin(
      RelationalDescriptorTableAccess owner, SchemaPin pin,
      TableDescriptor table, RelationalDescriptorScanCursor cursor) {
    if (cursor == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (cursor.isActive()) return StatusCode.CONFLICT;
    long space = RelationalDescriptorKeyspace.baseRows(table.tableId());
    StatusCode status = session.beginScan(
        space, Long.MIN_VALUE, space + 1, Long.MIN_VALUE, cursor.indexed());
    if (!status.isOk()) return status;
    cursor.markPhysicalOpen();
    status = cursor.claim(owner, pin);
    if (!status.isOk()) return cleanupFailedBegin(cursor, status);
    status = active.admit(cursor);
    return status.isOk() ? status : cleanupClaimedBegin(cursor, status);
  }

  StatusCode beginIndex(
      RelationalDescriptorTableAccess owner, SchemaPin pin,
      TableDescriptor table, RelationalDescriptorIndexBounds bounds,
      LockMode serializableSourceMode,
      RelationalDescriptorScanCursor cursor) {
    if (cursor == null || bounds == null
        || serializableSourceMode == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (cursor.isActive()) return StatusCode.CONFLICT;
    StatusCode status = cursor.tupleBounds().prepare(bounds);
    if (status.isOk() && serializableSourceMode == LockMode.EXCLUSIVE
        && !cursor.tupleBounds().exactUnique()) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk() && !cursor.tupleBounds().empty()) status = session.beginTupleScan(
        table.tableId(), bounds.key().keyId(), bounds.key().keyId(), bounds.key().shape(),
        cursor.tupleBounds().storage(), serializableSourceMode, cursor.tupleIndexed());
    if (!status.isOk()) {
      cursor.tupleBounds().clear();
      return status;
    }
    if (cursor.tupleBounds().empty()) cursor.markTupleEmptyOpen();
    else cursor.markTuplePhysicalOpen();
    status = cursor.claim(owner, pin);
    if (!status.isOk()) return cleanupFailedBegin(cursor, status);
    status = active.admit(cursor);
    return status.isOk() ? status : cleanupClaimedBegin(cursor, status);
  }

  StatusCode next(
      RelationalDescriptorTableAccess owner, RelationalDescriptorScanCursor cursor,
      SqlValueBuffer destination, RelationalRowIdentityResult result) {
    if (cursor == null || destination == null || result == null || !cursor.matches(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    TableDescriptor table = cursor.descriptor();
    int textBytes = maximumTextBytes(table);
    StatusCode status = textBytes < 0 ? StatusCode.RESOURCE_EXHAUSTED
        : destination.reserve(
            table.columnCount(), SqlShapeLimits.MAX_TABLE_COLUMNS,
            textBytes, TableSchema.MAXIMUM_ROW_BYTES);
    if (status.isOk()) status = rowAccess.reserve(table);
    if (!status.isOk()) return status;
    while (true) {
      status = nextPhysical(cursor);
      if (!status.isOk()) return status;
      long logicalRowId = cursor.logicalRowId();
      status = cursor.isTuplePhysical()
          ? rowAccess.fetch(session, table, logicalRowId, destination)
          : rowAccess.decode(table, logicalRowId, cursor.row().row(), destination);
      if (status == StatusCode.CONFLICT && cursor.isTuplePhysical()) continue;
      if (!status.isOk()) return status;
      if (cursor.isTuplePhysical()) {
        status = cursor.tupleBounds().recheck(destination);
        if (!status.isOk()) return status;
        if (!cursor.tupleBounds().matches()) continue;
      }
      result.set(logicalRowId);
      return StatusCode.OK;
    }
  }

  private StatusCode nextPhysical(RelationalDescriptorScanCursor cursor) {
    if (cursor.isEmptyPhysical()) return StatusCode.CONFLICT;
    StatusCode status = cursor.isTuplePhysical()
        ? session.nextTupleScan(cursor.tupleIndexed(), cursor.tupleRow())
        : session.nextScan(cursor.indexed(), cursor.row());
    if (status.isOk()) cursor.logicalRowId(cursor.isTuplePhysical()
        ? cursor.tupleRow().logicalRowId() : cursor.row().key());
    return status;
  }

  StatusCode close(
      RelationalDescriptorTableAccess owner, RelationalDescriptorScanCursor cursor) {
    if (cursor == null || !cursor.isOwnedBy(owner)) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = !cursor.isPhysicalOpen() || cursor.isEmptyPhysical() ? StatusCode.OK
        : cursor.isTuplePhysical() ? session.closeTupleScan(cursor.tupleIndexed())
            : session.closeScan(cursor.indexed());
    if (status.isOk()) cursor.markPhysicalClosed();
    if (status.isOk()) status = cursor.complete();
    if (status.isOk()) status = active.release(cursor);
    return status;
  }

  StatusCode closeActive(RelationalDescriptorTableAccess owner) {
    StatusCode status = StatusCode.OK;
    while (status.isOk() && active.count() > 0) status = close(owner, active.last());
    return status;
  }

  private StatusCode cleanupFailedBegin(
      RelationalDescriptorScanCursor cursor, StatusCode original) {
    StatusCode cleanup = cursor.isEmptyPhysical() ? StatusCode.OK : cursor.isTuplePhysical()
        ? session.closeTupleScan(cursor.tupleIndexed()) : session.closeScan(cursor.indexed());
    if (cleanup.isOk()) cursor.markPhysicalClosed();
    return cleanup.isOk() ? original : cleanup;
  }

  private StatusCode cleanupClaimedBegin(
      RelationalDescriptorScanCursor cursor, StatusCode original) {
    StatusCode cleanup = cursor.isEmptyPhysical() ? StatusCode.OK : cursor.isTuplePhysical()
        ? session.closeTupleScan(cursor.tupleIndexed()) : session.closeScan(cursor.indexed());
    if (cleanup.isOk()) cursor.markPhysicalClosed();
    if (cleanup.isOk()) cleanup = cursor.complete();
    return cleanup.isOk() ? original : cleanup;
  }

  private static int maximumTextBytes(TableDescriptor table) {
    long bytes = 0;
    for (int index = 0; index < table.columnCount(); index++) {
      int descriptor = table.typeDescriptorAt(index);
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += SqlTypeDescriptor.parameterOne(descriptor) * 4L;
        if (bytes > TableSchema.MAXIMUM_ROW_BYTES) return -1;
      }
    }
    return (int) bytes;
  }
}
