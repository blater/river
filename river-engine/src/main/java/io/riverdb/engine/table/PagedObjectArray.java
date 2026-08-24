package io.riverdb.engine.table;

/** Lazily allocated reference pages for sparse persisted-page metadata. */
final class PagedObjectArray<T> {
  private static final int PAGE_SHIFT = PagedIntArray.PAGE_SHIFT;
  private static final int PAGE_SIZE = PagedIntArray.PAGE_SIZE;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final Object[][] pages;

  PagedObjectArray(int maximumElements) {
    pages = new Object[(int) (((long) maximumElements + PAGE_SIZE) >>> PAGE_SHIFT)][];
  }

  @SuppressWarnings("unchecked")
  T get(int index) {
    if (index < 0) return null;
    int page = index >>> PAGE_SHIFT;
    return page < pages.length && pages[page] != null
        ? (T) pages[page][index & PAGE_MASK] : null;
  }

  void set(int index, T value) {
    if (index < 0) return;
    int page = index >>> PAGE_SHIFT;
    if (page >= pages.length) return;
    Object[] values = pages[page];
    if (values == null) values = pages[page] = new Object[PAGE_SIZE];
    values[index & PAGE_MASK] = value;
  }
}
