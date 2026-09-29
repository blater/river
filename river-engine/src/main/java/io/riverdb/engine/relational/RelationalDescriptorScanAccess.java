package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.tx.api.lock.LockMode;

/** Owns bounded allocation-free descriptor scans for its transaction session. */
final class RelationalDescriptorScanAccess {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorPrimaryAccess primary;
  private final RelationalDescriptorScanRegistry active =
      new RelationalDescriptorScanRegistry();
  private final RelationalDescriptorIndexBounds fullBounds =
      new RelationalDescriptorIndexBounds();

  RelationalDescriptorScanAccess(
      IndexedTransactionSession indexedSession, RelationalDescriptorPointViews pointViews) {
    session = indexedSession;
    primary = new RelationalDescriptorPrimaryAccess(pointViews);
  }

  StatusCode begin(
      RelationalDescriptorTableAccess owner, SchemaPin pin,
      TableDescriptor table, RelationalDescriptorScanCursor cursor) {
    StatusCode status = fullBounds.set(
        table.clusteredKey(), null, 0, true, null, 0, true,
        io.riverdb.storage.btree.TupleBTreeScanBounds.FORWARD);
    return status.isOk()
        ? beginIndex(owner, pin, table, fullBounds, LockMode.SHARED, cursor) : status;
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
      StoredTableRowView destination, RelationalRowIdentityResult result,
      StoredTableRowIntegerFilter filter, StoredTableColumnSelection selection) {
    if (cursor == null || destination == null || result == null || !cursor.matches(owner)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode reset = cursor.releaseView();
    if (reset.isOk()) reset = destination.reset();
    if (!reset.isOk()) return reset;
    TableDescriptor table = cursor.descriptor();
    if (selection != null && !selection.matches(table.columnCount())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = cursor.prepareSelection(selection, filter);
    if (!status.isOk()) return status;
    while (true) {
      status = nextPhysical(cursor);
      if (!status.isOk()) return status;
      long logicalRowId = cursor.logicalRowId();
      boolean primaryLeaf = cursor.isTuplePhysical()
          && cursor.tupleBounds().key() == table.clusteredKey();
      status = primaryLeaf
          ? bindPrimaryLeaf(table, cursor, destination, filter, selection)
          : cursor.isTuplePhysical()
              ? fetchSecondaryLocator(
                  table, cursor, destination, filter, selection)
              : destination.bindFetched(table, cursor.row().row(), filter, selection);
      if (status == StatusCode.CONFLICT && cursor.isTuplePhysical()) continue;
      if (status == StatusCode.CONFLICT && filter != null) continue;
      if (!status.isOk()) return status;
      if (cursor.isTuplePhysical()
          && (!primaryLeaf || cursor.tupleRow().pending())) {
        status = cursor.tupleBounds().recheck(destination);
        if (!status.isOk()) return status;
        if (!cursor.tupleBounds().matches()) continue;
      }
      result.set(logicalRowId);
      cursor.publishView(destination);
      return StatusCode.OK;
    }
  }

  private StatusCode fetchSecondaryLocator(
      TableDescriptor table, RelationalDescriptorScanCursor cursor,
      StoredTableRowView destination, StoredTableRowIntegerFilter filter,
      StoredTableColumnSelection selection) {
    var row = cursor.tupleRow();
    if (row.valueLength() <= 0 || row.valueLength() > cursor.pendingRow().capacity()) {
      return StatusCode.CORRUPTION;
    }
    if (row.pending()) {
      row.copyPendingValueTo(cursor.pendingRow(), 0);
      return primary.fetchLocator(
          session, table, cursor.pendingRow(), 0, row.valueLength(),
          row.logicalRowId(), destination, filter, selection);
    }
    return primary.fetchLocator(
        session, table, row.page(), row.valueOffset(), row.valueLength(),
        row.logicalRowId(), destination, filter, selection);
  }

  private static StatusCode bindPrimaryLeaf(
      TableDescriptor table, RelationalDescriptorScanCursor cursor,
      StoredTableRowView destination, StoredTableRowIntegerFilter filter,
      StoredTableColumnSelection selection) {
    var row = cursor.tupleRow();
    if (row.valueLength() <= 0 || row.valueLength() > cursor.pendingRow().capacity()) {
      return StatusCode.CORRUPTION;
    }
    if (row.pending()) {
      row.copyPendingValueTo(cursor.pendingRow(), 0);
      return destination.bindPinned(
          table, cursor.pendingRow(), 0, row.valueLength(), filter, selection);
    }
    return destination.bindPinned(
        table, row.page(), row.valueOffset(), row.valueLength(), filter, selection);
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
    StatusCode status = cursor.releaseView();
    if (!status.isOk()) return status;
    status = !cursor.isPhysicalOpen() || cursor.isEmptyPhysical() ? StatusCode.OK
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

}
