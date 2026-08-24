package io.riverdb.engine.table;

/** Sparse boolean values addressed by a positive 32-bit logical row id. */
final class LongPagedBooleanArray {
  private static final int PAGE_SHIFT = 12;
  private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final byte[][] pages;

  LongPagedBooleanArray(long maximumElements) {
    long pageCount = (maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT;
    if (pageCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("logical row address space is too large");
    }
    pages = new byte[(int) pageCount][];
  }

  boolean get(long index) {
    if (index < 0 || (index >>> PAGE_SHIFT) >= pages.length) return false;
    byte[] page = pages[(int) (index >>> PAGE_SHIFT)];
    return page != null && page[(int) index & PAGE_MASK] != 0;
  }

  void set(long index, boolean value) {
    if (index < 0 || (index >>> PAGE_SHIFT) >= pages.length) return;
    int pageIndex = (int) (index >>> PAGE_SHIFT);
    byte[] page = pages[pageIndex];
    if (page == null) page = pages[pageIndex] = new byte[PAGE_SIZE];
    page[(int) index & PAGE_MASK] = (byte) (value ? 1 : 0);
  }

  void clear() {
    for (byte[] page : pages) {
      if (page != null) java.util.Arrays.fill(page, (byte) 0);
    }
  }
}
