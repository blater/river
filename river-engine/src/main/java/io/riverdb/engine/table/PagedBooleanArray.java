package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Lazily allocated fixed-size pages for sparse boolean metadata indexed by a positive row id. */
final class PagedBooleanArray {
  private static final int PAGE_SHIFT = PagedIntArray.PAGE_SHIFT;
  private static final int PAGE_SIZE = PagedIntArray.PAGE_SIZE;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private final byte[][] pages;
  private final IndexedPagedArrayAllocator allocator;

  PagedBooleanArray(int maximumElements) {
    this(maximumElements, HeapIndexedPagedArrayAllocator.INSTANCE);
  }

  PagedBooleanArray(int maximumElements, IndexedPagedArrayAllocator pageAllocator) {
    pages = new byte[(int) (((long) maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT)][];
    allocator = pageAllocator;
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
      if (!value || !reserve(index).isOk()) return;
      values = pages[page];
    }
    values[index & PAGE_MASK] = (byte) (value ? 1 : 0);
  }

  StatusCode reserve(int index) {
    if (index <= 0 || (index >>> PAGE_SHIFT) >= pages.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int page = index >>> PAGE_SHIFT;
    if (pages[page] != null) return StatusCode.OK;
    try {
      byte[] allocated = allocator.allocateBytes(PAGE_SIZE);
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
    for (byte[] page : pages) {
      if (page != null) java.util.Arrays.fill(page, (byte) 0);
    }
  }
}
