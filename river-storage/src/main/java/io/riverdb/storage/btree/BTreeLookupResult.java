package io.riverdb.storage.btree;

/** Caller-owned result for a B+tree point lookup. */
public final class BTreeLookupResult {
  private long rowId;

  public long rowId() {
    return rowId;
  }

  public void setRowId(long value) {
    rowId = value;
  }

  public void reset() {
    rowId = 0;
  }
}
