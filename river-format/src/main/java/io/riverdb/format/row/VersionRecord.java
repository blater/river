package io.riverdb.format.row;

/** Caller-owned decoded MVCC version-directory record. */
public final class VersionRecord {
  private int tableId;
  private long versionId;
  private long logicalRowId;
  private long previousVersionId;
  private long commitSequence;
  private long pageNumber;
  private long pageGeneration;
  private int heapSlotId;
  private int slotGeneration;
  private boolean deleted;

  void set(
      int ownerTableId,
      long rowVersionId,
      long stableRowId,
      long previousId,
      long committedAt,
      long physicalPageNumber,
      long physicalPageGeneration,
      int localSlotId,
      int localSlotGeneration,
      boolean tombstone) {
    tableId = ownerTableId;
    versionId = rowVersionId;
    logicalRowId = stableRowId;
    previousVersionId = previousId;
    commitSequence = committedAt;
    pageNumber = physicalPageNumber;
    pageGeneration = physicalPageGeneration;
    heapSlotId = localSlotId;
    slotGeneration = localSlotGeneration;
    deleted = tombstone;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0, 0, false); }
  public int tableId() { return tableId; }
  public long versionId() { return versionId; }
  public long logicalRowId() { return logicalRowId; }
  public long previousVersionId() { return previousVersionId; }
  public long commitSequence() { return commitSequence; }
  public long pageNumber() { return pageNumber; }
  public long pageGeneration() { return pageGeneration; }
  public int heapSlotId() { return heapSlotId; }
  public int slotGeneration() { return slotGeneration; }
  public boolean deleted() { return deleted; }
}
