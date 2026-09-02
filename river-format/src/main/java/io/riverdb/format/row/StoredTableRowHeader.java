package io.riverdb.format.row;

/** Caller-owned decoded identity from one stored table-row header. */
public final class StoredTableRowHeader {
  private long rowLayoutId;
  private long logicalRowId;

  void set(long layoutId, long rowId) {
    rowLayoutId = layoutId;
    logicalRowId = rowId;
  }

  public void reset() {
    set(0, 0);
  }

  public long rowLayoutId() {
    return rowLayoutId;
  }

  public long logicalRowId() {
    return logicalRowId;
  }
}
