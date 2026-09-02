package io.riverdb.storage.btree;

/** Caller-owned whole-tree graph validation summary. */
public final class TupleBTreeValidationResult {
  private int height;
  private int pageCount;
  private int leafCount;
  private long entryCount;

  void set(int levels, int pages, int leaves, long entries) {
    height = levels;
    pageCount = pages;
    leafCount = leaves;
    entryCount = entries;
  }

  public void reset() { set(0, 0, 0, 0); }
  public int height() { return height; }
  public int pageCount() { return pageCount; }
  public int leafCount() { return leafCount; }
  public long entryCount() { return entryCount; }
}
