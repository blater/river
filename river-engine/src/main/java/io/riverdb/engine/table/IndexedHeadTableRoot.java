package io.riverdb.engine.table;

/** Reusable location and value for one table's logical-head tree root. */
final class IndexedHeadTableRoot {
  private long tableId;
  private int leafPageId;
  private int slot;
  private int rootPageId;
  private int rootLevel;

  void reset() {
    tableId = 0;
    leafPageId = 0;
    slot = 0;
    rootPageId = 0;
    rootLevel = 0;
  }

  void set(int leafId, int entrySlot, int rootId, int level) {
    leafPageId = leafId;
    slot = entrySlot;
    rootPageId = rootId;
    rootLevel = level;
  }

  void tableId(long id) { tableId = id; }
  long tableId() { return tableId; }

  int leafPageId() { return leafPageId; }
  int slot() { return slot; }
  int rootPageId() { return rootPageId; }
  int rootLevel() { return rootLevel; }
}
