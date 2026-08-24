package io.riverdb.engine.table;

/** Sparse int values addressed by a positive 32-bit logical row id. */
final class LongPagedIntArray {
  private static final int PAGE_SHIFT = 12;
  private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final int[][] pages;

  LongPagedIntArray(long maximumElements) {
    long pageCount = (maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT;
    if (pageCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("logical row address space is too large");
    }
    pages = new int[(int) pageCount][];
  }

  int get(long index) {
    if (index < 0 || (index >>> PAGE_SHIFT) >= pages.length) return 0;
    int[] page = pages[(int) (index >>> PAGE_SHIFT)];
    return page == null ? 0 : page[(int) index & PAGE_MASK];
  }

  void set(long index, int value) {
    if (index < 0 || (index >>> PAGE_SHIFT) >= pages.length) return;
    int pageIndex = (int) (index >>> PAGE_SHIFT);
    int[] page = pages[pageIndex];
    if (page == null) page = pages[pageIndex] = new int[PAGE_SIZE];
    page[(int) index & PAGE_MASK] = value;
  }

  void clear() {
    for (int[] page : pages) {
      if (page != null) java.util.Arrays.fill(page, 0);
    }
  }
}
