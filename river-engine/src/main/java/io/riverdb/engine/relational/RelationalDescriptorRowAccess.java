package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import java.nio.ByteBuffer;

/** Reusable encoded-row workspace shared by point, scan, and mutation access. */
final class RelationalDescriptorRowAccess {
  private final RelationalDescriptorRowBuffer buffer = new RelationalDescriptorRowBuffer();

  StatusCode reserve(TableDescriptor table) {
    return buffer.reserve(table.encodedMaximumRowBytes());
  }

  StatusCode encode(TableDescriptor table, SqlValueAccess values) {
    return buffer.encode(table, values);
  }

  StatusCode fetch(
      IndexedTransactionSession session, TableDescriptor table,
      long logicalRowId, StoredTableRowView destination) {
    destination.reset();
    destination.fetched().retentionProjection(null);
    StatusCode status = session.fetchByKey(
        RelationalDescriptorKeyspace.baseRows(table.tableId()),
        logicalRowId, destination.fetched());
    return status.isOk()
        ? destination.bindFetched(table, destination.fetched(), null, null) : status;
  }

  ByteBuffer bytes() { return buffer.bytes(); }
  int length() { return buffer.length(); }
}
