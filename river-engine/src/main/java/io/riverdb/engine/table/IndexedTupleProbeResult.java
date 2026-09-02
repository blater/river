package io.riverdb.engine.table;

/** Reusable result for one committed tuple user-key prefix probe. */
public final class IndexedTupleProbeResult {
  private boolean found;
  private long logicalRowId;

  public void reset() {
    found = false;
    logicalRowId = 0;
  }

  public boolean found() { return found; }
  public long logicalRowId() { return logicalRowId; }

  void set(long rowId) {
    found = true;
    logicalRowId = rowId;
  }
}
