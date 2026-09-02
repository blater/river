package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;

/** Caller-owned cursor over the primary and ready secondary indexes of one table. */
public final class CatalogIndexCursor {
  private final IndexedScanCursor indexed = new IndexedScanCursor();
  private final CatalogIndexCursorSecondaries secondaries =
      new CatalogIndexCursorSecondaries();
  private RelationalSession owner;
  private int tableId;
  private boolean primaryPending;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    tableId = 0;
    secondaries.reset();
    primaryPending = false;
    return indexed.reset();
  }

  IndexedScanCursor indexed() {
    return indexed;
  }

  StatusCode claim(
      RelationalSession session,
      int ownerTableId,
      int readySecondaryIndexes,
      int secondaryIndexes) {
    if (active
        || session == null
        || ownerTableId <= 0
        || !indexed.isActive()) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = secondaries.prepare(readySecondaryIndexes, secondaryIndexes);
    if (!status.isOk()) return status;
    owner = session;
    tableId = ownerTableId;
    primaryPending = true;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(RelationalSession session) {
    return active && owner == session;
  }

  int tableId() {
    return tableId;
  }

  boolean takePrimary() {
    boolean available = primaryPending;
    primaryPending = false;
    return available;
  }

  boolean recordSecondary(int slot) {
    return secondaries.record(slot);
  }

  boolean allSecondariesObserved() {
    return secondaries.complete();
  }

  public boolean isActive() {
    return active;
  }

  void complete() {
    owner = null;
    tableId = 0;
    secondaries.reset();
    primaryPending = false;
    active = false;
  }
}
