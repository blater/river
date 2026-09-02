package io.riverdb.format.row;

/** Caller-owned decoded MVCC version-directory record. */
public final class VersionRecord {
  private long objectId;
  private long heapVersionId;
  private long logicalRowId;
  private long previousHeapVersionId;
  private long commitSequence;
  private long pageNumber;
  private long pageGeneration;
  private int heapSlotId;
  private int slotGeneration;
  private boolean deleted;

  void set(
      long ownerObjectId,
      long currentHeapVersionId,
      long stableLogicalRowId,
      long priorHeapVersionId,
      long committedAt,
      long physicalPageNumber,
      long physicalPageGeneration,
      int localSlotId,
      int localSlotGeneration,
      boolean tombstone) {
    objectId = ownerObjectId;
    heapVersionId = currentHeapVersionId;
    logicalRowId = stableLogicalRowId;
    previousHeapVersionId = priorHeapVersionId;
    commitSequence = committedAt;
    pageNumber = physicalPageNumber;
    pageGeneration = physicalPageGeneration;
    heapSlotId = localSlotId;
    slotGeneration = localSlotGeneration;
    deleted = tombstone;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0, 0, false); }
  public long objectId() { return objectId; }
  public long heapVersionId() { return heapVersionId; }
  public long logicalRowId() { return logicalRowId; }
  public long previousHeapVersionId() { return previousHeapVersionId; }
  public long commitSequence() { return commitSequence; }
  public long pageNumber() { return pageNumber; }
  public long pageGeneration() { return pageGeneration; }
  public int heapSlotId() { return heapSlotId; }
  public int slotGeneration() { return slotGeneration; }
  public boolean deleted() { return deleted; }
}
