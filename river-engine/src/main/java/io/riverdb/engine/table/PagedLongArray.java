package io.riverdb.engine.table;

/** Lazily allocated fixed-size pages for sparse long metadata indexed by a positive row id. */
final class PagedLongArray {
  private static final int PAGE_SHIFT = PagedIntArray.PAGE_SHIFT;
  private static final int PAGE_SIZE = PagedIntArray.PAGE_SIZE;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final long[][] pages;

  PagedLongArray(int maximumElements) {
    pages = new long[(int) (((long) maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT)][];
  }

  long get(int index) {
    if (index <= 0) return 0;
    int page = index >>> PAGE_SHIFT;
    return page < pages.length && pages[page] != null ? pages[page][index & PAGE_MASK] : 0;
  }

  void set(int index, long value) {
    if (index <= 0 || (index >>> PAGE_SHIFT) >= pages.length) return;
    int page = index >>> PAGE_SHIFT;
    long[] values = pages[page];
    if (values == null) {
      values = pages[page] = new long[PAGE_SIZE];
    }
    values[index & PAGE_MASK] = value;
  }

  void clear() {
    for (long[] page : pages) {
      if (page != null) java.util.Arrays.fill(page, 0);
    }
  }
}
