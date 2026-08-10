package io.riverdb.engine.relational;

/** Caller-owned output for a logical relational database. */
public final class RelationalDatabaseOpenResult {
  private RelationalDatabase database;

  public void reset() {
    database = null;
  }

  void set(RelationalDatabase opened) {
    database = opened;
  }

  public RelationalDatabase database() {
    return database;
  }
}
