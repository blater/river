package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Finds descriptor tables that can reference one changed parent row. */
final class RelationalDescriptorForeignKeyChecks {
  private final RelationalSession owner;
  private final RelationalDatabaseServices services;
  private final IndexedTransactionSession session;
  private final IndexedScanCursor cursor = new IndexedScanCursor();
  private final IndexedScanResult row = new IndexedScanResult();
  private final RelationalDescriptorNameRow name = new RelationalDescriptorNameRow();
  private final SchemaPin child = new SchemaPin();
  private final RelationalDescriptorReferenceCheck referenceCheck;

  RelationalDescriptorForeignKeyChecks(
      RelationalSession relationalSession,
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices) {
    owner = relationalSession;
    session = indexedSession;
    services = databaseServices;
    referenceCheck = new RelationalDescriptorReferenceCheck(indexedSession);
  }

  StatusCode checkUpdate(
      TableDescriptor parent, SqlValueBuffer before, SqlValueBuffer after,
      long changedRowId) {
    return scan(parent, before, after, changedRowId);
  }

  StatusCode checkDelete(
      TableDescriptor parent, SqlValueBuffer before, long changedRowId) {
    return scan(parent, before, null, changedRowId);
  }

  StatusCode checkDrop(TableDescriptor parent) {
    return scan(parent, null, null, 0);
  }

  private StatusCode scan(
      TableDescriptor parent, SqlValueBuffer before, SqlValueBuffer after,
      long changedRowId) {
    StatusCode status = cursor.reset();
    if (status.isOk()) status = session.beginScan(
        RelationalDescriptorKeyspace.NAME_MAP_SPACE, Long.MIN_VALUE,
        RelationalDescriptorKeyspace.NAME_MAP_SPACE + 1, Long.MIN_VALUE, cursor);
    boolean active = status.isOk();
    while (status.isOk()) {
      row.reset();
      status = session.nextScan(cursor, row);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) status = name.read(row);
      if (status.isOk()) status = openChild();
      if (status.isOk()) {
        status = before == null
            ? checkDropReference(parent, child.descriptor())
            : referenceCheck.check(
                parent, before, after, child.descriptor(), changedRowId);
      }
      if (child.isActive()) {
        StatusCode released = child.release();
        if (status.isOk()) status = released;
      }
    }
    if (active) {
      StatusCode closed = session.closeScan(cursor);
      if (status.isOk()) status = closed;
    }
    return status;
  }

  private StatusCode checkDropReference(
      TableDescriptor parent, TableDescriptor childDescriptor) {
    if (parent.tableId() == childDescriptor.tableId()) return StatusCode.OK;
    return referenceCheck.references(parent, childDescriptor)
        ? StatusCode.FOREIGN_KEY_VIOLATION : StatusCode.OK;
  }

  private StatusCode openChild() {
    StatusCode status = owner.resolveDescriptor(name, child, null);
    return status == StatusCode.CONFLICT
        ? services.descriptors().open(name.objectId(), child, null) : status;
  }
}
