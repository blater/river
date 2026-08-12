package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Caller-owned output for an opened River database. */
public final class DatabaseOpenResult {
  private RiverDatabase database;

  public void reset() {
    database = null;
  }

  public StatusCode complete(RiverDatabase opened) {
    if (opened == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    database = opened;
    return StatusCode.OK;
  }

  public RiverDatabase database() {
    return database;
  }
}
