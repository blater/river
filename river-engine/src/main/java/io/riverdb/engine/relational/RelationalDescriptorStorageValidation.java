package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.engine.table.IndexedTupleProbeResult;
import io.riverdb.storage.heap.HeapRowResult;

/** Allocation-bounded validation of durable descriptor rows and tuple primary identity. */
final class RelationalDescriptorStorageValidation {
  private final IndexedTransactionSession session;
  private final RelationalDescriptorStorageValidationScan scan;
  private final RelationalDescriptorRowValidation rows;
  private final RelationalDescriptorForeignKeyStorageValidation foreignKeys;
  private final RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
  private final IndexedTupleProbeResult primary = new IndexedTupleProbeResult();
  private TableDescriptor table;
  private long objectId;

  RelationalDescriptorStorageValidation(
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices) {
    session = indexedSession;
    scan = new RelationalDescriptorStorageValidationScan(indexedSession, this);
    rows = new RelationalDescriptorRowValidation(databaseServices);
    foreignKeys = new RelationalDescriptorForeignKeyStorageValidation(
        indexedSession, databaseServices);
  }

  StatusCode validate(TableDescriptor descriptor) {
    table = descriptor;
    objectId = descriptor == null ? 0 : descriptor.tableId();
    StatusCode status;
    try {
      status = rows.begin(descriptor);
      if (status.isOk()) status = foreignKeys.validate(descriptor);
      if (status.isOk()) status = scan.validate(
          RelationalDescriptorKeyspace.baseRows(objectId));
    } catch (OutOfMemoryError error) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    status = finish(status, scan.close());
    status = finish(status, rows.complete());
    table = null;
    objectId = 0;
    return status;
  }

  StatusCode validateBase(long logicalRowId, HeapRowResult row) {
    if (logicalRowId <= 0) return StatusCode.CORRUPTION;
    StatusCode status = rows.decode(logicalRowId, row);
    if (!status.isOk()) return status;
    if (table.primaryKey() == null) return StatusCode.OK;
    status = encoder.encodeUser(table.primaryKey(), rows.values());
    if (status.isOk()) status = session.probeTuplePrefix(
        objectId, table.primaryKey().keyId(), table.primaryKey().keyId(),
        table.primaryKey().shape(), encoder.bytes(), 0, encoder.length(), primary);
    return status.isOk() && (!primary.found() || primary.logicalRowId() != logicalRowId)
        ? StatusCode.CORRUPTION : status;
  }

  private static StatusCode finish(StatusCode status, StatusCode cleanup) {
    return status.isOk() ? cleanup : status;
  }
}
