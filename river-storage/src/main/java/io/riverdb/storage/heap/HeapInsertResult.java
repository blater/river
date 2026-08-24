package io.riverdb.storage.heap;

/** Caller-owned identity of one inserted heap row. */
public final class HeapInsertResult {
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
