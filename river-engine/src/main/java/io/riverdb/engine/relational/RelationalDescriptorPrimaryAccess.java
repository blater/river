package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleProbeResult;
import io.riverdb.engine.table.IndexedTupleScanCursor;
import io.riverdb.engine.table.IndexedTupleScanResult;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.storage.btree.TupleBTreeScanBounds;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.tx.api.lock.LockMode;
import java.nio.ByteBuffer;

/** Reusable primary-key point resolution and scalar compatibility workspace. */
final class RelationalDescriptorPrimaryAccess {
  private final IndexedTupleProbeResult probe = new IndexedTupleProbeResult();
  private final RelationalTupleKeyEncoder expectedEncoder = new RelationalTupleKeyEncoder();
  private final RelationalTupleKeyEncoder actualEncoder = new RelationalTupleKeyEncoder();
  private final SqlMutationValues scalarValues = new SqlMutationValues();
  private final TupleBTreeScanBounds pointBounds = new TupleBTreeScanBounds();
  private final RelationalDescriptorPointViews pointViews;
  private final ByteBuffer locatorKey = ByteBuffer.allocate(
      TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES);

  RelationalDescriptorPrimaryAccess() { this(null); }

  RelationalDescriptorPrimaryAccess(RelationalDescriptorPointViews views) {
    pointViews = views;
  }

  StatusCode fetch(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueAccess primaryValues, StoredTableRowView destination,
      RelationalRowIdentityResult result) {
    if (result != null) result.reset();
    StatusCode status = destination.reset();
    if (status.isOk()) status = expectedEncoder.encodeUser(table.primaryKey(), primaryValues);
    if (!status.isOk()) return status;
    return fetchEncoded(
        session, table, expectedEncoder.bytes(), expectedEncoder.length(),
        0, destination, null, null, result);
  }

  StatusCode fetchLocator(
      IndexedTransactionSession session, TableDescriptor table,
      ByteBuffer locator, int offset, int length, long expectedRowId,
      StoredTableRowView destination, StoredTableRowIntegerFilter filter,
      StoredTableColumnSelection selection) {
    if (locator == null || length <= TupleKeyCodec.LOGICAL_ROW_ID_BYTES
        || length > locatorKey.capacity() || offset < 0
        || offset > locator.limit() - length
        || table.primaryKey() == null) return StatusCode.CORRUPTION;
    long rowId = 0;
    for (int index = length - Long.BYTES; index < length; index++) {
      rowId = rowId << 8 | Byte.toUnsignedInt(locator.get(offset + index));
    }
    if (rowId != expectedRowId) return StatusCode.CORRUPTION;
    int userLength = length - TupleKeyCodec.LOGICAL_ROW_ID_BYTES;
    for (int index = 0; index < userLength; index++) {
      locatorKey.put(index, locator.get(offset + index));
    }
    locatorKey.put(1, (byte) (locatorKey.get(1) & ~TupleKeyCodec.FLAG_PHYSICAL));
    return fetchEncoded(
        session, table, locatorKey, userLength,
        expectedRowId, destination, filter, selection, null);
  }

  private StatusCode fetchEncoded(
      IndexedTransactionSession session, TableDescriptor table,
      ByteBuffer encoded, int encodedLength, long expectedRowId,
      StoredTableRowView destination, StoredTableRowIntegerFilter filter,
      StoredTableColumnSelection selection, RelationalRowIdentityResult result) {
    StatusCode status = pointBounds.setRange(
        encoded, 0, encodedLength, table.primaryKey().shape(), true,
        encoded, 0, encodedLength, table.primaryKey().shape(), true,
        TupleBTreeScanBounds.FORWARD);
    if (!status.isOk()) return status;
    IndexedTupleScanCursor pointCursor;
    IndexedTupleScanResult pointRow;
    try {
      pointCursor = destination.pointCursor();
      pointRow = destination.pointRow();
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    status = session.beginTupleScan(
        table.tableId(), table.primaryKey().keyId(), table.primaryKey().keyId(),
        table.primaryKey().shape(), pointBounds, LockMode.SHARED, pointCursor);
    if (!status.isOk()) return status;
    status = session.nextTupleScan(pointCursor, pointRow);
    if (status.isOk()) {
      if (pointRow.pending()) {
        if (pointRow.valueLength() <= 0
            || pointRow.valueLength() > HeapPage.MAXIMUM_ROW_BYTES) {
          status = StatusCode.CORRUPTION;
        } else {
          ByteBuffer pendingRow;
          try {
            pendingRow = destination.pointPending(pointRow.valueLength());
          } catch (OutOfMemoryError error) {
            return closeFailedPoint(session, pointCursor, StatusCode.RESOURCE_EXHAUSTED);
          }
          pointRow.copyPendingValueTo(pendingRow, 0);
          status = destination.bindPinned(
              table, pendingRow, 0, pointRow.valueLength(), filter, selection);
        }
      } else {
        status = destination.bindPinned(
            table, pointRow.page(), pointRow.valueOffset(), pointRow.valueLength(),
            filter, selection);
      }
      if (status.isOk() && expectedRowId > 0
          && pointRow.logicalRowId() != expectedRowId) status = StatusCode.CONFLICT;
      if (status.isOk() && result != null) result.set(pointRow.logicalRowId());
    }
    if (!status.isOk() || pointViews == null) {
      return closeFailedPoint(session, pointCursor, status);
    }
    status = destination.holdPoint(session, pointViews);
    return status.isOk() ? status : closeFailedPoint(session, pointCursor, status);
  }

  private static StatusCode closeFailedPoint(
      IndexedTransactionSession session, IndexedTupleScanCursor cursor, StatusCode original) {
    StatusCode closed = session.closeTupleScan(cursor);
    return closed.isOk() ? original : closed;
  }

  StatusCode resolve(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueAccess primaryValues, RelationalRowIdentityResult result) {
    return resolve(session, table, primaryValues, result, null);
  }

  StatusCode resolveSource(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueAccess primaryValues, LockMode mode,
      RelationalRowIdentityResult result) {
    return resolve(session, table, primaryValues, result, mode);
  }

  private StatusCode resolve(
      IndexedTransactionSession session, TableDescriptor table,
      SqlValueAccess primaryValues, RelationalRowIdentityResult result,
      LockMode sourceMode) {
    result.reset();
    if (table.primaryKey() == null || primaryValues == null
        || primaryValues.count() != table.columnCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = expectedEncoder.encodeUser(table.primaryKey(), primaryValues);
    if (!status.isOk()) return status;
    status = sourceMode != null
        ? session.resolveTupleUniqueSource(
            table.tableId(), table.primaryKey().keyId(), table.primaryKey().keyId(),
            table.primaryKey().shape(), expectedEncoder.bytes(), 0,
            expectedEncoder.length(), sourceMode, probe)
        : session.resolveTupleUniquePrefix(
            table.tableId(), table.primaryKey().keyId(), table.primaryKey().keyId(),
            table.primaryKey().shape(), expectedEncoder.bytes(), 0,
            expectedEncoder.length(), probe);
    if (!status.isOk()) return status;
    if (!probe.found()) return StatusCode.CONFLICT;
    result.set(probe.logicalRowId());
    return StatusCode.OK;
  }

  StatusCode validateResolved(TableDescriptor table, SqlValueAccess values) {
    StatusCode status = actualEncoder.encodeUser(table.primaryKey(), values);
    if (!status.isOk()) return status;
    if (expectedEncoder.length() != actualEncoder.length()) return StatusCode.CONFLICT;
    ByteBuffer expected = expectedEncoder.bytes();
    ByteBuffer actual = actualEncoder.bytes();
    for (int index = 0; index < expectedEncoder.length(); index++) {
      if (expected.get(index) != actual.get(index)) return StatusCode.CONFLICT;
    }
    return StatusCode.OK;
  }

  StatusCode scalarValues(TableDescriptor table, long primaryKey) {
    if (table == null || table.primaryKey() == null
        || table.primaryKey().partCount() != 1
        || table.primaryKey().columnOrdinalAt(0) != 0
        || table.typeDescriptorAt(0) != SqlTypeDescriptor.BIGINT) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    scalarValues.reset();
    StatusCode status = scalarValues.reserve(table, 0);
    if (status.isOk()) status = scalarValues.begin(table, null);
    return status.isOk()
        ? scalarValues.setFixed(0, table.typeDescriptorAt(0), primaryKey) : status;
  }

  SqlValueAccess scalarValues() { return scalarValues; }
}
