package io.riverdb.format.wal;

/** Caller-owned decoded root publication from one indexed WAL batch. */
public final class IndexedRootUpdate {
  private int kind;
  private int ownerId;
  private int pageId;
  private long pageGeneration;

  void set(int rootKind, int owner, int page, long generation) {
    kind = rootKind;
    ownerId = owner;
    pageId = page;
    pageGeneration = generation;
  }

  public void reset() {
    set(0, 0, 0, 0);
  }

  public int kind() { return kind; }
  public int ownerId() { return ownerId; }
  public int pageId() { return pageId; }
  public long pageGeneration() { return pageGeneration; }
}
