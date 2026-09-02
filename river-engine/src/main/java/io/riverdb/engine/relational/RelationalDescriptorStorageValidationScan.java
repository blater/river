package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Reusable exact-lifetime cursor for one descriptor storage validation pass. */
final class RelationalDescriptorStorageValidationScan {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorStorageValidation validation;
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult row = new IndexedScanResult();

  RelationalDescriptorStorageValidationScan(
      IndexedTransactionSession indexedSession,
      RelationalDescriptorStorageValidation storageValidation) {
    session = indexedSession;
    validation = storageValidation;
  }

  StatusCode validate(long space) {
    StatusCode status = cursor.reset();
    if (status.isOk()) status = session.beginScan(
        space, Long.MIN_VALUE, space + 1, Long.MIN_VALUE, cursor);
    while (status.isOk()) {
      row.reset();
      status = session.nextScan(cursor, row);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = validation.validateBase(row.key(), row.row());
    }
    StatusCode closed = close();
    return status.isOk() ? closed : status;
  }

  StatusCode close() {
    return cursor.isActive() ? session.closeScan(cursor) : StatusCode.OK;
  }
}
