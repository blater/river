package io.riverdb.engine.table;

/** Reusable sparse row-leaf location for one table scan. */
final class IndexedHeadLeafResult {
  private int pageId;
  private long ordinal;

  void reset() { pageId = 0; ordinal = 0; }
  void set(int valuePageId, long valueOrdinal) {
    pageId = valuePageId;
    ordinal = valueOrdinal;
  }
  int pageId() { return pageId; }
  long ordinal() { return ordinal; }
}
