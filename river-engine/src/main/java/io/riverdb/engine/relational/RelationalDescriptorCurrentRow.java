package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedLockedRow;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleScanResult;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Protects one logical identity and reads its current clustered successor. */
final class RelationalDescriptorCurrentRow {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorPrimaryAccess primary;
  private final IndexedLockedRow locked = new IndexedLockedRow();
  private final ByteBuffer pendingLocator = ByteBuffer.allocate(
      TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES);
  private long logicalRowId;

  RelationalDescriptorCurrentRow(
      IndexedTransactionSession indexedSession,
      RelationalDescriptorPrimaryAccess primaryAccess) {
    session = indexedSession;
    primary = primaryAccess;
  }

  StatusCode lockPoint(
      TableDescriptor table, SqlValueAccess primaryValues, long rowId,
      StoredTableRowView destination) {
    StatusCode status = acquire(table, rowId);
    if (status.isOk()) status = primary.fetchCurrentPoint(
        session, table, primaryValues, rowId, destination);
    return finish(rowId, destination, status);
  }

  StatusCode lockScan(
      RelationalDescriptorScanCursor cursor, StoredTableRowView destination) {
    if (!cursor.isTuplePhysical()) return StatusCode.INVALID_EXTERNAL_INPUT;
    TableDescriptor table = cursor.descriptor();
    long rowId = cursor.logicalRowId();
    StatusCode status = acquire(table, rowId);
    if (!status.isOk()) return status;
    IndexedTupleScanResult source = cursor.tupleRow();
    boolean clustered = cursor.tupleBounds().key() == table.clusteredKey();
    int length = clustered ? source.keyLength() : source.valueLength();
    if (length <= TupleKeyCodec.LOGICAL_ROW_ID_BYTES
        || length > pendingLocator.capacity()) return finish(
            rowId, destination, StatusCode.CORRUPTION);
    ByteBuffer locator = clustered ? source.keyPage() : source.page();
    int offset = clustered ? source.keyOffset() : source.valueOffset();
    if (source.pending()) {
      if (clustered) source.copyPendingKeyTo(pendingLocator, 0);
      else source.copyPendingValueTo(pendingLocator, 0);
      locator = pendingLocator;
      offset = 0;
    }
    status = primary.fetchCurrentLocator(
        session, table, locator, offset, length, rowId, destination);
    if (status == StatusCode.CONFLICT && !clustered) {
      status = primary.fetchCurrentByIdentity(session, table, rowId, destination);
    }
    if (status.isOk() && (!clustered || table.primaryKey() != null)) {
      status = cursor.tupleBounds().recheck(destination);
      if (status.isOk() && !cursor.tupleBounds().matches()) status = StatusCode.CONFLICT;
    }
    return finish(rowId, destination, status);
  }

  StatusCode lockLogical(
      TableDescriptor table, long rowId, StoredTableRowView destination) {
    StatusCode status = acquire(table, rowId);
    if (status.isOk()) status = primary.fetchCurrentByIdentity(
        session, table, rowId, destination);
    return finish(rowId, destination, status);
  }

  IndexedLockedRow locked() { return locked; }
  long logicalRowId() { return logicalRowId; }
  boolean borrowed() { return locked.isAvailable(); }

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

  private StatusCode acquire(TableDescriptor table, long rowId) {
    logicalRowId = 0;
    return session.borrowTupleRowLock(
        RelationalDescriptorKeyspace.baseRows(table.tableId()), rowId, locked);
  }

  private StatusCode finish(
      long rowId, StoredTableRowView destination, StatusCode status) {
    if (status.isOk()) {
      logicalRowId = rowId;
      return status;
    }
    StatusCode reset = destination.reset();
    StatusCode released = locked.isAvailable() ? release() : StatusCode.OK;
    return !reset.isOk() ? reset : released.isOk() ? status : released;
  }
}
