package io.riverdb.engine.table;

/** Reusable status-bearing row-directory lookup result. */
final class IndexedRowLocation {
  private int pageId;
  private int slot;

  void reset() {
    pageId = 0;
    slot = 0;
  }

  void set(int rowPageId, int rowSlot) {
    pageId = rowPageId;
    slot = rowSlot;
  }

  int pageId() {
    return pageId;
  }

  int slot() {
    return slot;
  }
}
