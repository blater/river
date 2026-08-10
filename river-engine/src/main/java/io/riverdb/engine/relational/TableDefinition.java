package io.riverdb.engine.relational;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  private RelationalDatabase owner;
  private int tableId;
  private int uniqueValueIndexTableId;
  private long schemaVersion;
  private boolean available;

  public void reset() {
    owner = null;
    tableId = 0;
    uniqueValueIndexTableId = 0;
    schemaVersion = 0;
    available = false;
  }

  void set(RelationalDatabase database, int id, int valueIndexTableId) {
    owner = database;
    tableId = id;
    uniqueValueIndexTableId = valueIndexTableId;
    schemaVersion = database.schemaVersion();
    available = true;
  }

  public int tableId() {
    return tableId;
  }

  public boolean isAvailable() {
    return available;
  }

  public boolean hasUniqueValueIndex() {
    return uniqueValueIndexTableId > 0;
  }

  int uniqueValueIndexTableId() {
    return uniqueValueIndexTableId;
  }

  boolean isOwnedBy(RelationalDatabase database) {
    return available
        && owner == database
        && schemaVersion == database.schemaVersion();
  }
}
