package io.riverdb.format.btree;

/** Caller-owned decoded B-tree internal entry. */
public final class BTreeInternalEntry {
  private long space;
  private long key;
  private int rightChildPageId;

  void set(long keySpace, long orderedKey, int childPageId) {
    space = keySpace;
    key = orderedKey;
    rightChildPageId = childPageId;
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

  public int rightChildPageId() {
    return rightChildPageId;
  }
}
