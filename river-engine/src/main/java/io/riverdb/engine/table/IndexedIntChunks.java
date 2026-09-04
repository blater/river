package io.riverdb.engine.table;

import static io.riverdb.engine.runtime.DatabasePrimitiveChunkLayout.DIRECTORY_BITS;
import static io.riverdb.engine.runtime.DatabasePrimitiveChunkLayout.DIRECTORY_MASK;
import static io.riverdb.engine.runtime.DatabasePrimitiveChunkLayout.DIRECTORY_SIZE;
import static io.riverdb.engine.runtime.DatabasePrimitiveChunkLayout.PAGE_MASK;
import static io.riverdb.engine.runtime.DatabasePrimitiveChunkLayout.PAGE_SHIFT;
import static io.riverdb.engine.runtime.DatabasePrimitiveChunkLayout.PAGE_SIZE;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabasePrimitiveChunkLayout;

/** Lazily retained radix-addressed primitive integer pages for a bounded owner. */
final class IndexedIntChunks {
  private final int maximum;
  private final int rootLength;
  private int[][][][] root;
  private int allocatedPages;

  IndexedIntChunks(int maximumElements) {
    if (maximumElements < 0) throw new IllegalArgumentException("negative chunk capacity");
    maximum = maximumElements;
    rootLength = DatabasePrimitiveChunkLayout.rootLength(maximumElements);
  }

  StatusCode reserve(int required) {
    if (required < 0 || required > maximum) return StatusCode.RESOURCE_EXHAUSTED;
    int pagesRequired = (int) (((long) required + PAGE_MASK) >>> PAGE_SHIFT);
    try {
      if (pagesRequired > 0 && root == null) root = new int[rootLength][][][];
      while (allocatedPages < pagesRequired) {
        allocatePage(allocatedPages);
        allocatedPages++;
      }
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int capacity() { return maximum; }
  int allocatedCapacity() {
    return (int) Math.min(maximum, (long) allocatedPages * PAGE_SIZE);
  }
  long allocatedBytes() {
    if (root == null) return 0;
    return DatabasePrimitiveChunkLayout.retainedBytes(
        maximum, allocatedCapacity(), Integer.BYTES);
  }
  long accountedBytesForCapacity(int required) {
    return DatabasePrimitiveChunkLayout.retainedBytes(
        maximum, required, Integer.BYTES);
  }
  int get(int index) { return page(index)[index & PAGE_MASK]; }
  void set(int index, int value) { page(index)[index & PAGE_MASK] = value; }
  void release() {
    root = null;
    allocatedPages = 0;
  }

  private void allocatePage(int page) {
    int branch = page >>> 16;
    int directory = (page >>> DIRECTORY_BITS) & DIRECTORY_MASK;
    if (root[branch] == null) {
      root[branch] = new int[DIRECTORY_SIZE][][];
    }
    if (root[branch][directory] == null) {
      root[branch][directory] = new int[DIRECTORY_SIZE][];
    }
    root[branch][directory][page & DIRECTORY_MASK] = new int[PAGE_SIZE];
  }

  private int[] page(int index) {
    int page = index >>> PAGE_SHIFT;
    return root[page >>> 16][(page >>> DIRECTORY_BITS) & DIRECTORY_MASK]
        [page & DIRECTORY_MASK];
  }
}
