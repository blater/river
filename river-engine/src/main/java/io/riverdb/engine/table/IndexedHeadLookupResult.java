package io.riverdb.engine.table;

/** Reusable latest physical version from a table-local logical head. */
final class IndexedHeadLookupResult {
  private long rowId;
  private int leafPageId;
  private int slot;

  void reset() { rowId = 0; leafPageId = 0; slot = 0; }
  void set(long value, int pageId, int entrySlot) {
    rowId = value;
    leafPageId = pageId;
    slot = entrySlot;
  }
  long rowId() { return rowId; }
  int leafPageId() { return leafPageId; }
  int slot() { return slot; }
}
