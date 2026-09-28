package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable encoded-row workspace shared by point, scan, and mutation access. */
final class RelationalDescriptorRowAccess {
  private final RelationalDescriptorRowBuffer buffer = new RelationalDescriptorRowBuffer();
  private final HeapRowResult fetched = new HeapRowResult();

  StatusCode reserve(TableDescriptor table) {
    return buffer.reserve(table.encodedMaximumRowBytes());
  }

  StatusCode encode(TableDescriptor table, long logicalRowId, SqlValueBuffer values) {
    return buffer.encode(table, logicalRowId, values);
  }

  StatusCode fetch(
      IndexedTransactionSession session, TableDescriptor table,
      long logicalRowId, SqlValueBuffer destination) {
    return fetch(session, table, logicalRowId, destination, null);
  }

  StatusCode fetch(
      IndexedTransactionSession session, TableDescriptor table,
      long logicalRowId, SqlValueBuffer destination, StoredTableRowIntegerFilter filter) {
    return fetch(session, table, logicalRowId, destination, filter, null);
  }

  StatusCode fetch(
      IndexedTransactionSession session, TableDescriptor table,
      long logicalRowId, SqlValueBuffer destination, StoredTableRowIntegerFilter filter,
      StoredTableColumnSelection selection) {
    fetched.reset();
    StatusCode status = selection == null ? StatusCode.OK
        : selection.prepareProjection(table);
    if (!status.isOk()) return status;
    fetched.retentionProjection(selection == null ? null : selection.projection());
    status = session.fetchByKey(
        RelationalDescriptorKeyspace.baseRows(table.tableId()), logicalRowId, fetched);
    return status.isOk()
        ? decode(table, logicalRowId, fetched, destination, filter, selection) : status;
  }

  StatusCode decode(
      TableDescriptor table, long logicalRowId,
      HeapRowResult source, SqlValueBuffer destination) {
    return decode(table, logicalRowId, source, destination, null);
  }

  StatusCode decode(
      TableDescriptor table, long logicalRowId,
      HeapRowResult source, SqlValueBuffer destination, StoredTableRowIntegerFilter filter) {
    return decode(table, logicalRowId, source, destination, filter, null);
  }

  StatusCode decode(
      TableDescriptor table, long logicalRowId,
      HeapRowResult source, SqlValueBuffer destination, StoredTableRowIntegerFilter filter,
      StoredTableColumnSelection selection) {
    if (selection != null) {
      StatusCode status = selection.prepareProjection(table);
      if (!status.isOk()) return status;
      if (selection.projection() != null) {
        source.retentionProjection(selection.projection());
        if (source.retainedReadOnlyBytes() == null) {
          status = source.retainBytes();
          if (!status.isOk()) return status;
        }
      }
    }
    return buffer.decode(table, logicalRowId, source, destination, filter, selection);
  }

  ByteBuffer bytes() { return buffer.bytes(); }
  int length() { return buffer.length(); }
}
