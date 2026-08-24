package io.riverdb.engine.table;

/** Lazily allocated fixed-size pages for sparse boolean metadata indexed by a positive row id. */
final class PagedBooleanArray {
  private static final int PAGE_SHIFT = PagedIntArray.PAGE_SHIFT;
  private static final int PAGE_SIZE = PagedIntArray.PAGE_SIZE;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final byte[][] pages;

  PagedBooleanArray(int maximumElements) {
    pages = new byte[(int) (((long) maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT)][];
  }

  boolean get(int index) {
    if (index <= 0) return false;
    int page = index >>> PAGE_SHIFT;
    return page < pages.length && pages[page] != null && pages[page][index & PAGE_MASK] != 0;
  }

  void set(int index, boolean value) {
    if (index <= 0 || (index >>> PAGE_SHIFT) >= pages.length) return;
    int page = index >>> PAGE_SHIFT;
    byte[] values = pages[page];
    if (values == null) {
      values = pages[page] = new byte[PAGE_SIZE];
    }
    values[index & PAGE_MASK] = (byte) (value ? 1 : 0);
  }

  void clear() {
    for (byte[] page : pages) {
      if (page != null) java.util.Arrays.fill(page, (byte) 0);
    }
  }
}
