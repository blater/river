package io.riverdb.storage.heap;

/** Caller-owned identity of one inserted heap row. */
public final class HeapInsertResult {
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
