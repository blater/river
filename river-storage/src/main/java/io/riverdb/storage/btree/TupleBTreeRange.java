package io.riverdb.storage.btree;

/** Caller-owned half-open range of matching slots within one validated leaf page. */
public final class TupleBTreeRange {
  private int first;
  private int limit;

  void set(int from, int to) {
    first = from;
    limit = to;
  }

  public void reset() { set(0, 0); }
  public int first() { return first; }
  public int limit() { return limit; }
  public int count() { return limit - first; }
}
