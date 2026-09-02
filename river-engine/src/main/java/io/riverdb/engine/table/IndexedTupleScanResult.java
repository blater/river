package io.riverdb.engine.table;

/** Caller-owned logical-row identity returned by a transactional tuple-index scan. */
public final class IndexedTupleScanResult {
  private long logicalRowId;

  public void reset() { logicalRowId = 0; }
  public long logicalRowId() { return logicalRowId; }
  void set(long rowId) { logicalRowId = rowId; }
}
