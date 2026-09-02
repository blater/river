package io.riverdb.engine.table;

/** Selects an empty or oldest unpinned frame under bounded cache pressure. */
final class IndexedPageFrameSelection {
  private IndexedPageFrameSelection() {}

  static int reusable(IndexedPageFrame[] frames, boolean allowEviction) {
    int oldest = -1;
    long oldestAccess = Long.MAX_VALUE;
    for (int index = 0; index < frames.length; index++) {
      IndexedPageFrame frame = frames[index];
      if (frame == null || frame.pageId == 0) return index;
      if (allowEviction && frame.pinCount == 0 && frame.access < oldestAccess) {
        oldest = index;
        oldestAccess = frame.access;
      }
    }
    return oldest;
  }
}
