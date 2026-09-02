package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedScanCursor;
import io.riverdb.engine.table.IndexedScanResult;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.storage.heap.HeapRowResult;

/** Startup validation of durable catalog-v2 name ownership and uniqueness. */
final class RelationalDescriptorNameValidation {
  private final IndexedTransactionSession session;
  private final RelationalDatabaseServices services;
  private final IndexedScanCursor names = new IndexedScanCursor();
  private final IndexedScanResult row = new IndexedScanResult();
  private final HeapRowResult earlierRow = new HeapRowResult();
  private final RelationalDescriptorNameRow name = new RelationalDescriptorNameRow();
  private final RelationalDescriptorNameRow earlierName = new RelationalDescriptorNameRow();
  private final RelationalDescriptorNameSet seen = new RelationalDescriptorNameSet();
  private final SchemaPin pin = new SchemaPin();
  private final StatusDetail detail = new StatusDetail(128);
  private RelationalDescriptorStorageValidation storage;

  RelationalDescriptorNameValidation(
      IndexedTransactionSession indexedSession,
      RelationalDatabaseServices databaseServices) {
    session = indexedSession;
    services = databaseServices;
  }

  StatusCode validate() {
    if (storage == null) {
      try {
        storage = new RelationalDescriptorStorageValidation(session, services);
      } catch (OutOfMemoryError error) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    seen.reset();
    StatusCode status = names.reset();
    if (status.isOk()) status = session.beginScan(
        RelationalDescriptorKeyspace.NAME_MAP_SPACE,
        Long.MIN_VALUE,
        RelationalDescriptorKeyspace.NAME_MAP_SPACE + 1,
        Long.MIN_VALUE,
        names);
    while (status.isOk()) {
      row.reset();
      status = session.nextScan(names, row);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      status = name.read(row);
      if (status.isOk()) status = validateOwner();
      if (status.isOk()) status = validateUnique();
    }
    StatusCode closed = session.closeScan(names);
    return status.isOk() ? closed : status;
  }

  private StatusCode validateOwner() {
    detail.reset();
    StatusCode status = services.descriptors().open(name.objectId(), pin, detail);
    if (status == StatusCode.CONFLICT) return StatusCode.CORRUPTION;
    if (!status.isOk()) return status;
    boolean matches = pin.tableId() == name.objectId() && pin.isPublished();
    if (matches) status = storage.validate(pin.descriptor());
    StatusCode released = pin.release();
    if (!matches) return StatusCode.CORRUPTION;
    return status.isOk() ? released : status;
  }

  private StatusCode validateUnique() {
    StatusCode status = seen.reserveInsert();
    if (!status.isOk()) return status;
    long hash = name.hash();
    int slot = seen.first(hash);
    while (seen.objectIdAt(slot) != 0) {
      if (seen.hashAt(slot) == hash) {
        earlierRow.reset();
        status = session.fetchByKey(
            RelationalDescriptorKeyspace.NAME_MAP_SPACE,
            seen.objectIdAt(slot),
            earlierRow);
        if (status == StatusCode.CONFLICT) return StatusCode.CORRUPTION;
        if (!status.isOk()) return status;
        status = earlierName.read(seen.objectIdAt(slot), earlierRow);
        if (!status.isOk()) return status;
        if (earlierName.matches(name.bytes(), name.byteLength())) return StatusCode.CORRUPTION;
      }
      slot = seen.next(slot);
    }
    seen.insert(slot, hash, name.objectId());
    return StatusCode.OK;
  }
}
