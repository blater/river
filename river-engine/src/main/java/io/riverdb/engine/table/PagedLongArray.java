package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Lazily allocated fixed-size pages for sparse long metadata indexed by a positive row id. */
final class PagedLongArray {
  private static final int PAGE_SHIFT = PagedIntArray.PAGE_SHIFT;
  private static final int PAGE_SIZE = PagedIntArray.PAGE_SIZE;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final long[][] pages;
  private final IndexedPagedArrayAllocator allocator;

  PagedLongArray(int maximumElements) {
    this(maximumElements, HeapIndexedPagedArrayAllocator.INSTANCE);
  }

  PagedLongArray(int maximumElements, IndexedPagedArrayAllocator pageAllocator) {
    pages = new long[(int) (((long) maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT)][];
    allocator = pageAllocator;
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
      if (value == 0 || !reserve(index).isOk()) return;
      values = pages[page];
    }
    values[index & PAGE_MASK] = value;
  }

  StatusCode reserve(int index) {
    if (index <= 0 || (index >>> PAGE_SHIFT) >= pages.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int page = index >>> PAGE_SHIFT;
    if (pages[page] != null) return StatusCode.OK;
    try {
      long[] allocated = allocator.allocateLongs(PAGE_SIZE);
      if (allocated == null || allocated.length < PAGE_SIZE) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      pages[page] = allocated;
      return StatusCode.OK;
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void clear() {
    for (long[] page : pages) {
      if (page != null) java.util.Arrays.fill(page, 0);
    }
  }
}
