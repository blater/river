package io.riverdb.format.row;

/** Caller-owned decoded stable logical-row directory record. */
public final class LogicalRowRecord {
  private long objectId;
  private long logicalRowId;
  private long headHeapVersionId;
  private boolean keyless;

  void set(long ownerObjectId, long stableLogicalRowId, long heapVersionId, boolean hiddenKey) {
    objectId = ownerObjectId;
    logicalRowId = stableLogicalRowId;
    headHeapVersionId = heapVersionId;
    keyless = hiddenKey;
  }

  public void reset() { set(0, 0, 0, false); }
  public long objectId() { return objectId; }
  public long logicalRowId() { return logicalRowId; }
  public long headHeapVersionId() { return headHeapVersionId; }
  public boolean keyless() { return keyless; }
}
