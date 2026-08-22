package io.riverdb.format.btree;

/** Caller-owned typed leaf slot from a validated inline-tuple page. */
public final class TupleBTreeLeafEntry {
  private int keyOffset;
  private int keyLength;
  private long logicalRowId;

  void set(int offset, int length, long rowId) {
    keyOffset = offset;
    keyLength = length;
    logicalRowId = rowId;
  }

  public void reset() { set(0, 0, 0); }
  public int keyOffset() { return keyOffset; }
  public int keyLength() { return keyLength; }
  public long logicalRowId() { return logicalRowId; }
}
