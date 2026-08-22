package io.riverdb.format.wal;

/** Caller-owned decoded indexed page-batch header. */
public final class IndexedPageBatchHeader {
  private int pageCount;
  private int rootCount;
  private long maximumLogicalRowId;
  private long maximumVersionId;
  private int nextPageId;
  private long storageGeneration;

  void set(
      int pages,
      int roots,
      long maximumLogical,
      long maximumVersion,
      int nextPage,
      long generation) {
    pageCount = pages;
    rootCount = roots;
    maximumLogicalRowId = maximumLogical;
    maximumVersionId = maximumVersion;
    nextPageId = nextPage;
    storageGeneration = generation;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0);
  }

  public int pageCount() { return pageCount; }
  public int rootCount() { return rootCount; }
  public long maximumLogicalRowId() { return maximumLogicalRowId; }
  public long maximumVersionId() { return maximumVersionId; }
  public int nextPageId() { return nextPageId; }
  public long storageGeneration() { return storageGeneration; }
}
