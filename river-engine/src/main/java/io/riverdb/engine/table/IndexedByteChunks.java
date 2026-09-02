package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Lazily retained radix-addressed byte pages for one bounded variable payload. */
final class IndexedByteChunks {
  private static final int PAGE_SHIFT = 15;
  private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private static final int DIRECTORY_BITS = 8;
  private static final int DIRECTORY_SIZE = 1 << DIRECTORY_BITS;
  private static final int DIRECTORY_MASK = DIRECTORY_SIZE - 1;
  private final int maximum;
  private final int rootLength;
  private byte[][][][] root;
  private int allocatedPages;
  private int allocatedBranches;
  private int allocatedDirectories;

  IndexedByteChunks(int maximumBytes) {
    if (maximumBytes < 0) throw new IllegalArgumentException("negative chunk capacity");
    maximum = maximumBytes;
    long maximumPages = ((long) maximumBytes + PAGE_MASK) >>> PAGE_SHIFT;
    rootLength = (int) ((maximumPages + 65_535L) >>> 16);
    root = new byte[rootLength][][][];
  }

  StatusCode reserve(int required) {
    if (required < 0 || required > maximum) return StatusCode.RESOURCE_EXHAUSTED;
    int pagesRequired = (int) (((long) required + PAGE_MASK) >>> PAGE_SHIFT);
    try {
      while (allocatedPages < pagesRequired) {
        allocatePage(allocatedPages);
        allocatedPages++;
      }
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int allocatedBytes() {
    return (int) Math.min(maximum, (long) allocatedPages * PAGE_SIZE);
  }
  long retainedBytes() {
    return (long) allocatedPages * (PAGE_SIZE + 16L)
        + (long) allocatedBranches * (DIRECTORY_SIZE * 8L + 16L)
        + (long) allocatedDirectories * (DIRECTORY_SIZE * 8L + 16L)
        + (long) rootLength * 8L + 16L;
  }
  long retainedBytesForCapacity(int required) {
    if (required < 0 || required > maximum) return -1;
    long pages = ((long) required + PAGE_MASK) >>> PAGE_SHIFT;
    long branches = (pages + 65_535L) >>> 16;
    long directories = (pages + DIRECTORY_MASK) >>> DIRECTORY_BITS;
    return pages * (PAGE_SIZE + 16L)
        + branches * (DIRECTORY_SIZE * 8L + 16L)
        + directories * (DIRECTORY_SIZE * 8L + 16L)
        + (long) rootLength * 8L + 16L;
  }
  byte get(int index) { return page(index)[index & PAGE_MASK]; }
  void set(int index, byte value) { page(index)[index & PAGE_MASK] = value; }
  void copyTo(int offset, ByteBuffer target, int targetOffset, int length) {
    for (int index = 0; index < length; index++) {
      target.put(targetOffset + index, get(offset + index));
    }
  }
  void release() {
    root = null;
    allocatedPages = allocatedBranches = allocatedDirectories = 0;
  }

  private void allocatePage(int page) {
    int branch = page >>> 16;
    int directory = (page >>> DIRECTORY_BITS) & DIRECTORY_MASK;
    if (root[branch] == null) {
      root[branch] = new byte[DIRECTORY_SIZE][][];
      allocatedBranches++;
    }
    if (root[branch][directory] == null) {
      root[branch][directory] = new byte[DIRECTORY_SIZE][];
      allocatedDirectories++;
    }
    root[branch][directory][page & DIRECTORY_MASK] = new byte[PAGE_SIZE];
  }

  private byte[] page(int index) {
    int page = index >>> PAGE_SHIFT;
    return root[page >>> 16][(page >>> DIRECTORY_BITS) & DIRECTORY_MASK]
        [page & DIRECTORY_MASK];
  }
}
