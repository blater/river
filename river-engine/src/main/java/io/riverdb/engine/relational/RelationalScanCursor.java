package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;

/** Caller-owned cursor for one logical table's ordered visible rows. */
public final class RelationalScanCursor {
  private final IndexedScanCursor indexed = new IndexedScanCursor();
  private RelationalSession owner;
  private int indexedColumn = -1;
  private long duplicateEntryId;
  private long duplicateValue;
  private int duplicateEntriesVisited;
  private boolean uniqueIndex;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    indexedColumn = -1;
    duplicateEntryId = 0;
    duplicateValue = 0;
    duplicateEntriesVisited = 0;
    uniqueIndex = false;
    return indexed.reset();
  }

  IndexedScanCursor indexed() {
    return indexed;
  }

  StatusCode claim(RelationalSession session) {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(RelationalSession session) {
    return active && owner == session;
  }

  StatusCode setIndexedColumn(
      RelationalSession session,
      int column,
      boolean unique) {
    if (!isOwnedBy(session) || column <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    indexedColumn = column;
    uniqueIndex = unique;
    return StatusCode.OK;
  }

  int indexedColumn() {
    return indexedColumn;
  }

  boolean uniqueIndex() {
    return uniqueIndex;
  }

  long duplicateEntryId() {
    return duplicateEntryId;
  }

  long duplicateValue() {
    return duplicateValue;
  }

  int duplicateEntriesVisited() {
    return duplicateEntriesVisited;
  }

  void startDuplicateChain(long value, long entryId) {
    duplicateValue = value;
    duplicateEntryId = entryId;
    duplicateEntriesVisited = 0;
  }

  void advanceDuplicateChain(long entryId) {
    duplicateEntryId = entryId;
    duplicateEntriesVisited++;
  }

  void complete() {
    active = false;
  }

  public boolean isActive() {
    return active;
  }
}
