package io.riverdb.storage.btree;

/** Caller-owned publication data produced by one leaf split. */
public final class BTreeSplitResult {
  private int separatorSpace;
  private long separatorKey;

  public int separatorSpace() {
    return separatorSpace;
  }

  public long separatorKey() {
    return separatorKey;
  }

  public void setSeparator(int space, long value) {
    separatorSpace = space;
    separatorKey = value;
  }

  public void reset() {
    separatorSpace = 0;
    separatorKey = 0;
  }
}
