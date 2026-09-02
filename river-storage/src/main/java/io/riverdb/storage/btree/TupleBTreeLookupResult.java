package io.riverdb.storage.btree;

/** Caller-owned exact-seek location and logical identity. */
public final class TupleBTreeLookupResult {
  private int pageId;
  private int index;
  private int keyOffset;
  private int keyLength;
  private long logicalRowId;

  void set(int entryIndex, int offset, int length, long rowId) {
    pageId = 0;
    index = entryIndex;
    keyOffset = offset;
    keyLength = length;
    logicalRowId = rowId;
  }

  void setTree(int sourcePageId, int entryIndex, int offset, int length, long rowId) {
    set(entryIndex, offset, length, rowId);
    pageId = sourcePageId;
  }

  public void reset() { set(-1, 0, 0, 0); }
  public int pageId() { return pageId; }
  public int index() { return index; }
  public int keyOffset() { return keyOffset; }
  public int keyLength() { return keyLength; }
  public long logicalRowId() { return logicalRowId; }
}
