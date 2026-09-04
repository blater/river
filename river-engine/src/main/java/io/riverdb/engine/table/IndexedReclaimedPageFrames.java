package io.riverdb.engine.table;

/** Owns the allocation-free chain of empty current frames reclaimed from MVCC history. */
final class IndexedReclaimedPageFrames {
  private int head = -1;
  private int tail = -1;

  void offer(IndexedPageFrame[] frames, int slot) {
    IndexedPageFrame frame = frames[slot];
    frame.previousVersionSlot = -1;
    if (tail < 0) {
      head = slot;
    } else {
      frames[tail].previousVersionSlot = slot;
    }
    tail = slot;
  }

  int poll(IndexedPageFrame[] frames) {
    int slot = head;
    if (slot < 0) return -1;
    IndexedPageFrame frame = frames[slot];
    head = frame.previousVersionSlot;
    if (head < 0) tail = -1;
    frame.previousVersionSlot = -1;
    return slot;
  }

  void clear() {
    head = tail = -1;
  }
}
