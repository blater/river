package io.riverdb.engine.row;

/** Caller-owned immutable-until-refill reservation of non-reusable row identity ranges. */
public final class LogicalRowIdReservation {
  private long objectId;
  private long firstLogicalRowId;
  private int logicalRowCount;

  void set(long object, long logical, int logicalCount) {
    objectId = object;
    firstLogicalRowId = logical;
    logicalRowCount = logicalCount;
  }

  public void reset() { set(0, 0, 0); }
  public long objectId() { return objectId; }
  public long firstLogicalRowId() { return firstLogicalRowId; }
  public int logicalRowCount() { return logicalRowCount; }
}
