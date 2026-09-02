package io.riverdb.storage.btree;

/** Caller-owned publication data produced by one leaf split. */
public final class BTreeSplitResult {
  private long separatorSpace;
  private long separatorKey;

  public long separatorSpace() {
    return separatorSpace;
  }

  public long separatorKey() {
    return separatorKey;
  }

  public void setSeparator(long space, long value) {
    separatorSpace = space;
    separatorKey = value;
  }

  public void reset() {
    separatorSpace = 0;
    separatorKey = 0;
  }
}
