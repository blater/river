package io.riverdb.engine.relational;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  private RelationalDatabase owner;
  private int tableId;
  private boolean available;

  public void reset() {
    owner = null;
    tableId = 0;
    available = false;
  }

  void set(RelationalDatabase database, int id) {
    owner = database;
    tableId = id;
    available = true;
  }

  public int tableId() {
    return tableId;
  }

  public boolean isAvailable() {
    return available;
  }

  boolean isOwnedBy(RelationalDatabase database) {
    return available && owner == database;
  }
}
