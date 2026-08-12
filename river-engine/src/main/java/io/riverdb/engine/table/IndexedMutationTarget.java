package io.riverdb.engine.table;

/** Caller-owned resolved row-version target for a deferred update or delete. */
public final class IndexedMutationTarget {
  private int rowId;

  public int rowId() {
    return rowId;
  }

  public void set(int value) {
    rowId = value;
  }

  public void reset() {
    rowId = 0;
  }
}
