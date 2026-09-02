package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusDetail;

/** Caller-owned output for a logical relational database. */
public final class RelationalDatabaseOpenResult {
  private static final int DETAIL_CAPACITY = 512;

  private final StatusDetail detail = new StatusDetail(DETAIL_CAPACITY);
  private RelationalDatabase database;

  public void reset() {
    database = null;
    detail.reset();
  }

  void set(RelationalDatabase opened) {
    database = opened;
  }

  public RelationalDatabase database() {
    return database;
  }

  public StatusDetail detail() {
    return detail;
  }
}
