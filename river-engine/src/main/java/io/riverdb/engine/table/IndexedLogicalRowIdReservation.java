package io.riverdb.engine.table;

/** Caller-owned result for one exact contiguous logical-row-ID reservation. */
public final class IndexedLogicalRowIdReservation {
  private long objectId;
  private long firstLogicalRowId;
  private long nextLogicalRowId;
  private int logicalRowCount;

  void set(long object, long first, int count, long next) {
    objectId = object;
    firstLogicalRowId = first;
    logicalRowCount = count;
    nextLogicalRowId = next;
  }

  public void reset() {
    set(0, 0, 0, 0);
  }

  public long objectId() {
    return objectId;
  }

  public long firstLogicalRowId() {
    return firstLogicalRowId;
  }

  public int logicalRowCount() {
    return logicalRowCount;
  }

  public long nextLogicalRowId() {
    return nextLogicalRowId;
  }
}
