package io.riverdb.engine.relational;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  static final int INDEX_NONE = 0;
  static final int INDEX_BUILDING = 1;
  static final int INDEX_READY = 2;

  private RelationalDatabase owner;
  private int tableId;
  private int uniqueValueIndexTableId;
  private int uniqueValueIndexState;
  private long schemaVersion;
  private boolean available;

  public void reset() {
    owner = null;
    tableId = 0;
    uniqueValueIndexTableId = 0;
    uniqueValueIndexState = INDEX_NONE;
    schemaVersion = 0;
    available = false;
  }

  void set(
      RelationalDatabase database,
      int id,
      int valueIndexTableId,
      int valueIndexState) {
    owner = database;
    tableId = id;
    uniqueValueIndexTableId = valueIndexTableId;
    uniqueValueIndexState = valueIndexState;
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
    return uniqueValueIndexTableId > 0 && uniqueValueIndexState == INDEX_READY;
  }

  public boolean hasBuildingUniqueValueIndex() {
    return uniqueValueIndexTableId > 0 && uniqueValueIndexState == INDEX_BUILDING;
  }

  int uniqueValueIndexTableId() {
    return uniqueValueIndexTableId;
  }

  int uniqueValueIndexState() {
    return uniqueValueIndexState;
  }

  boolean isOwnedBy(RelationalDatabase database) {
    return available
        && owner == database
        && schemaVersion == database.schemaVersion();
  }
}
