package io.riverdb.engine.table;

/** Caller-owned resolved row-version target for a deferred update or delete. */
public final class IndexedMutationTarget {
  private long rowId;

  public long rowId() {
    return rowId;
  }

  public void set(long value) {
    rowId = value;
  }

  public void reset() {
    rowId = 0;
  }
}
