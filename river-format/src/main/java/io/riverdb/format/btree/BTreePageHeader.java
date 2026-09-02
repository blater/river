package io.riverdb.format.btree;

/** Caller-owned decoded B-tree payload header. */
public final class BTreePageHeader {
  private int type;
  private int entryCount;
  private int pointer;
  private long highSpace;
  private long highKey;

  void set(int pageType, int count, int pagePointer, long fenceSpace, long fenceKey) {
    type = pageType;
    entryCount = count;
    pointer = pagePointer;
    highSpace = fenceSpace;
    highKey = fenceKey;
  }

  public void reset() {
    set(0, 0, 0, 0, 0);
  }

  public int type() {
    return type;
  }

  public int entryCount() {
    return entryCount;
  }

  /** Right sibling for a leaf or first child for an internal page. */
  public int pointer() {
    return pointer;
  }

  public long highSpace() {
    return highSpace;
  }

  public long highKey() {
    return highKey;
  }
}
