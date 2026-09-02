package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Sparse mutable version pages used while constructing one checkpoint state. */
final class CheckpointVersionOverrides {
  private long[] pageIds = new long[4];
  private Page[] pages = new Page[4];
  private int count;
  private int cursor;
  private long obsoleteCount;

  StatusCode set(long rowId, long committedAt, long previousRowId, boolean deleted) {
    try {
      Page page = page((rowId - 1) >>> CheckpointVersionFormat.PAGE_SHIFT, true);
      int slot = (int) (rowId - 1) & CheckpointVersionFormat.PAGE_MASK;
      long oldPrevious = page.previousRowIds[slot];
      page.commitSequences[slot] = committedAt;
      page.previousRowIds[slot] = previousRowId;
      page.setDeleted(slot, deleted);
      if (oldPrevious == 0 && previousRowId > 0) obsoleteCount++;
      else if (oldPrevious > 0 && previousRowId == 0) obsoleteCount--;
      return StatusCode.OK;
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  boolean read(long rowId, long defaultCommit, CheckpointVersionResult result) {
    Page page = page((rowId - 1) >>> CheckpointVersionFormat.PAGE_SHIFT, false);
    if (page == null) return false;
    int slot = (int) (rowId - 1) & CheckpointVersionFormat.PAGE_MASK;
    long committedAt = page.commitSequences[slot];
    long previous = page.previousRowIds[slot];
    boolean deleted = page.deleted(slot);
    if (committedAt == 0 && previous == 0 && !deleted) return false;
    result.set(committedAt == 0 ? defaultCommit : committedAt, previous, deleted);
    return true;
  }

  int count() { return count; }
  long pageId(int index) { return index >= 0 && index < count ? pageIds[index] : -1; }
  long obsoleteCount() { return obsoleteCount; }
  void resetCursor() { cursor = 0; }
  long nextPageId() { return cursor < count ? pageIds[cursor++] : -1; }

  void clear() {
    Arrays.fill(pages, 0, count, null);
    count = 0;
    cursor = 0;
    obsoleteCount = 0;
  }

  private Page page(long pageId, boolean create) {
    int low = insertionPoint(pageId);
    if (low < count && pageIds[low] == pageId) return pages[low];
    if (!create) return null;
    ensureCapacity();
    if (low < count) {
      System.arraycopy(pageIds, low, pageIds, low + 1, count - low);
      System.arraycopy(pages, low, pages, low + 1, count - low);
    }
    pageIds[low] = pageId;
    pages[low] = new Page();
    count++;
    return pages[low];
  }

  private int insertionPoint(long pageId) {
    int low = 0;
    int high = count;
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (pageIds[middle] < pageId) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  private void ensureCapacity() {
    if (count < pages.length) return;
    pageIds = Arrays.copyOf(pageIds, pages.length << 1);
    pages = Arrays.copyOf(pages, pages.length << 1);
  }

  private static final class Page {
    private final long[] commitSequences = new long[CheckpointVersionFormat.PAGE_ROWS];
    private final long[] previousRowIds = new long[CheckpointVersionFormat.PAGE_ROWS];
    private final long[] deletedWords = new long[CheckpointVersionFormat.PAGE_ROWS / Long.SIZE];

    boolean deleted(int slot) {
      return (deletedWords[slot >>> 6] & 1L << (slot & 63)) != 0;
    }

    void setDeleted(int slot, boolean deleted) {
      long mask = 1L << (slot & 63);
      if (deleted) deletedWords[slot >>> 6] |= mask;
      else deletedWords[slot >>> 6] &= ~mask;
    }
  }
}
