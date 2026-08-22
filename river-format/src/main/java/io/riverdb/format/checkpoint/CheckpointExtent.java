package io.riverdb.format.checkpoint;

/** Caller-owned decoded bounded dirty-page extent. */
public final class CheckpointExtent {
  private int firstPageId;
  private int pageCount;
  private long flushGeneration;

  void set(int first, int count, long generation) {
    firstPageId = first;
    pageCount = count;
    flushGeneration = generation;
  }

  public void reset() { set(0, 0, 0); }
  public int firstPageId() { return firstPageId; }
  public int pageCount() { return pageCount; }
  public long flushGeneration() { return flushGeneration; }
}
