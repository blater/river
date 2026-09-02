package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Caller-owned output for an opened River database. */
public final class DatabaseOpenResult {
  public static final int DETAIL_CAPACITY = 512;

  private final StatusDetail detail = new StatusDetail(DETAIL_CAPACITY);
  private RiverDatabase database;

  public void reset() {
    database = null;
    detail.reset();
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

  public StatusDetail detail() {
    return detail;
  }
}
