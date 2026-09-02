package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.table.IndexedScanCursor;

/** Caller-owned cursor over visible table and view catalog records. */
public final class CatalogObjectCursor {
  private final IndexedScanCursor indexed = new IndexedScanCursor();
  private RelationalSession owner;
  private boolean descriptorPhase;
  private boolean active;

  public StatusCode reset() {
    if (active) {
      return StatusCode.CONFLICT;
    }
    owner = null;
    descriptorPhase = false;
    return indexed.reset();
  }

  IndexedScanCursor indexed() {
    return indexed;
  }

  StatusCode claim(RelationalSession session) {
    if (active || session == null || !indexed.isActive()) {
      return StatusCode.CONFLICT;
    }
    owner = session;
    active = true;
    return StatusCode.OK;
  }

  boolean isOwnedBy(RelationalSession session) {
    return active && owner == session;
  }

  boolean descriptorPhase() {
    return descriptorPhase;
  }

  void beginDescriptorPhase() {
    descriptorPhase = true;
  }

  public boolean isActive() {
    return active;
  }

  void complete() {
    owner = null;
    descriptorPhase = false;
    active = false;
  }
}
