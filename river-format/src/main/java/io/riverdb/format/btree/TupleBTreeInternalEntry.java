package io.riverdb.format.btree;

/** Caller-owned typed internal slot from a validated inline-tuple page. */
public final class TupleBTreeInternalEntry {
  private int keyOffset;
  private int keyLength;
  private int rightChildPageId;

  void set(int offset, int length, int childPageId) {
    keyOffset = offset;
    keyLength = length;
    rightChildPageId = childPageId;
  }

  public void reset() { set(0, 0, 0); }
  public int keyOffset() { return keyOffset; }
  public int keyLength() { return keyLength; }
  public int rightChildPageId() { return rightChildPageId; }
}
