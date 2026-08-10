package io.riverdb.storage.btree;

/** Caller-owned publication data produced by one leaf split. */
public final class BTreeSplitResult {
  private long separatorKey;

  public long separatorKey() {
    return separatorKey;
  }

  public void setSeparatorKey(long value) {
    separatorKey = value;
  }

  public void reset() {
    separatorKey = 0;
  }
}
