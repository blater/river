package io.riverdb.engine;

/** Caller-owned output for embedded database creation or open. */
public final class EmbeddedDatabaseOpenResult {
  private EmbeddedDatabase database;

  public void reset() {
    database = null;
  }

  public void set(EmbeddedDatabase opened) {
    database = opened;
  }

  public EmbeddedDatabase database() {
    return database;
  }
}
