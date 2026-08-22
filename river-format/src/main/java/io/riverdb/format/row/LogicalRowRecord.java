package io.riverdb.format.row;

/** Caller-owned decoded stable logical-row directory record. */
public final class LogicalRowRecord {
  private int tableId;
  private long logicalRowId;
  private long headVersionId;
  private boolean keyless;

  void set(int ownerTableId, long rowId, long versionId, boolean hiddenKey) {
    tableId = ownerTableId;
    logicalRowId = rowId;
    headVersionId = versionId;
    keyless = hiddenKey;
  }

  public void reset() { set(0, 0, 0, false); }
  public int tableId() { return tableId; }
  public long logicalRowId() { return logicalRowId; }
  public long headVersionId() { return headVersionId; }
  public boolean keyless() { return keyless; }
}
