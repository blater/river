package io.riverdb.storage.btree;

/** Caller-owned result for a B+tree point lookup. */
public final class BTreeLookupResult {
  private int rowId;

  public int rowId() {
    return rowId;
  }

  public void setRowId(int value) {
    rowId = value;
  }

  public void reset() {
    rowId = 0;
  }
}
