package io.riverdb.engine.table;

/** Lazily allocated fixed-size pages for sparse int metadata. */
final class PagedIntArray {
  static final int PAGE_SHIFT = 12;
  static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final int[][] pages;

  PagedIntArray(int maximumElements) {
    pages = new int[(int) (((long) maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT)][];
  }

  int get(int index) {
    if (index < 0) return 0;
    int page = index >>> PAGE_SHIFT;
    return page < pages.length && pages[page] != null ? pages[page][index & PAGE_MASK] : 0;
  }

  void set(int index, int value) {
    if (index < 0 || (index >>> PAGE_SHIFT) >= pages.length) return;
    int page = index >>> PAGE_SHIFT;
    int[] values = pages[page];
    if (values == null) {
      values = pages[page] = new int[PAGE_SIZE];
    }
    values[index & PAGE_MASK] = value;
  }

  void clear() {
    for (int[] page : pages) {
      if (page != null) java.util.Arrays.fill(page, 0);
    }
  }
}
