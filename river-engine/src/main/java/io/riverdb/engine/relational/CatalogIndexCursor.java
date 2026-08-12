package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;

/** Caller-owned cursor over the primary and ready secondary indexes of one table. */
public final class CatalogIndexCursor {
  private final IndexedScanCursor indexed = new IndexedScanCursor();
  private RelationalSession owner;
  private int tableId;
  private int expectedSecondaryIndexes;
  private int observedIndexMask;
  private boolean primaryPending;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    tableId = 0;
    expectedSecondaryIndexes = 0;
    observedIndexMask = 0;
    primaryPending = false;
    return indexed.reset();
  }

  IndexedScanCursor indexed() {
    return indexed;
  }

  StatusCode claim(
      RelationalSession session,
      int ownerTableId,
      int readySecondaryIndexes) {
    if (active
        || session == null
        || ownerTableId <= 0
        || readySecondaryIndexes < 0
        || readySecondaryIndexes > TableDefinition.MAXIMUM_INDEXES
        || !indexed.isActive()) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    tableId = ownerTableId;
    expectedSecondaryIndexes = readySecondaryIndexes;
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
    int bit = slot < 0 || slot >= TableDefinition.MAXIMUM_INDEXES ? 0 : 1 << slot;
    if (bit == 0 || (observedIndexMask & bit) != 0) {
      return false;
    }
    observedIndexMask |= bit;
    return true;
  }

  boolean allSecondariesObserved() {
    return Integer.bitCount(observedIndexMask) == expectedSecondaryIndexes;
  }

  public boolean isActive() {
    return active;
  }

  void complete() {
    owner = null;
    tableId = 0;
    expectedSecondaryIndexes = 0;
    observedIndexMask = 0;
    primaryPending = false;
    active = false;
  }
}
