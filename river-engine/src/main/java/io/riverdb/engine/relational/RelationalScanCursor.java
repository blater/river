package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;

/** Caller-owned cursor for one logical table's ordered visible rows. */
public final class RelationalScanCursor {
  private final IndexedScanCursor indexed = new IndexedScanCursor();
  private RelationalSession owner;
  private int indexedColumn = -1;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    indexedColumn = -1;
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

  StatusCode setIndexedColumn(RelationalSession session, int column) {
    if (!isOwnedBy(session) || column <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    indexedColumn = column;
    return StatusCode.OK;
  }

  int indexedColumn() {
    return indexedColumn;
  }

  void complete() {
    active = false;
  }

  public boolean isActive() {
    return active;
  }
}
