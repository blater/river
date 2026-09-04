package io.riverdb.storage.btree;

/** Caller-owned exact admission result for one read-only insert preflight. */
public final class TupleBTreeInsertPreflightResult {
  public static final int MAXIMUM_NEW_PAGES = TupleBTreeStructure.MAXIMUM_LEVELS;
  public static final int MAXIMUM_CHANGED_PAGES = MAXIMUM_NEW_PAGES * 2;

  private boolean keyExists;
  private boolean createsRoot;
  private int newPageCount;
  private int changedPageCount;
  private int splitLevelCount;
  private int resultingHeight;

  void set(
      boolean exists, boolean newRoot, int newPages, int changedPages,
      int splitLevels, int height) {
    keyExists = exists;
    createsRoot = newRoot;
    newPageCount = newPages;
    changedPageCount = changedPages;
    splitLevelCount = splitLevels;
    resultingHeight = height;
  }

  public void reset() { set(false, false, 0, 0, 0, 0); }
  public boolean keyExists() { return keyExists; }
  public boolean createsRoot() { return createsRoot; }
  public int newPageCount() { return newPageCount; }
  public int changedPageCount() { return changedPageCount; }
  public int splitLevelCount() { return splitLevelCount; }
  public int resultingHeight() { return resultingHeight; }
}
