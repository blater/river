package io.riverdb.format.btree;

/** Caller-owned decoded B-tree leaf entry. */
public final class BTreeLeafEntry {
  private long space;
  private long key;
  private long logicalRowId;

  void set(long keySpace, long orderedKey, long rowId) {
    space = keySpace;
    key = orderedKey;
    logicalRowId = rowId;
  }

  public void reset() {
    set(0, 0, 0);
  }

  public long space() {
    return space;
  }

  public long key() {
    return key;
  }

  public long logicalRowId() {
    return logicalRowId;
  }
}
