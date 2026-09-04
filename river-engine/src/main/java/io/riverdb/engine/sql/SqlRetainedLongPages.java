package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Session-budgeted primitive long pages addressed across the Java {@code int} index space. */
final class SqlRetainedLongPages implements SqlRetainedReclaimer {
  private static final int PAGE_SHIFT = 8;
  private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_SIZE - 1;
  private static final int DIRECTORY_BITS = 8;
  private static final int DIRECTORY_SIZE = 1 << DIRECTORY_BITS;
  private static final int DIRECTORY_MASK = DIRECTORY_SIZE - 1;
  private static final int ROOT_SIZE = 1 << 7;
  private static final long ARRAY_HEADER_BYTES = 16;
  private static final long ROOT_BYTES = ARRAY_HEADER_BYTES + ROOT_SIZE * (long) Long.BYTES;
  private static final long DIRECTORY_BYTES =
      ARRAY_HEADER_BYTES + DIRECTORY_SIZE * (long) Long.BYTES;
  private static final long PAGE_BYTES = ARRAY_HEADER_BYTES + PAGE_SIZE * (long) Long.BYTES;

  private final SqlSessionShapeBudget budget;
  private long[][][][] root;
  private long retainedBytes;
  private int count;
  private boolean registered;
  private boolean active;

  SqlRetainedLongPages(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode begin() {
    if (active) return StatusCode.CONFLICT;
    if (!registered && budget != null) {
      StatusCode status = budget.registerReclaimer(this);
      if (!status.isOk()) return status;
      registered = true;
    }
    count = 0;
    active = true;
    return StatusCode.OK;
  }

  StatusCode append(long value) {
    if (!active) return StatusCode.CONFLICT;
    if (count == Integer.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = reservePage(count >>> PAGE_SHIFT);
    if (!status.isOk()) return status;
    page(count)[count & PAGE_MASK] = value;
    count++;
    return StatusCode.OK;
  }

  long get(int index) {
    return page(index)[index & PAGE_MASK];
  }

  int count() {
    return count;
  }

  void finish() {
    active = false;
  }

  @Override
  public long reclaimableRetainedBytes() {
    return active ? 0 : retainedBytes;
  }

  @Override
  public void releaseRetainedStorage() {
    if (active) return;
    root = null;
    retainedBytes = 0;
    count = 0;
  }

  private StatusCode reservePage(int page) {
    if (root == null) {
      StatusCode status = reserve(ROOT_BYTES);
      if (!status.isOk()) return status;
      try {
        root = new long[ROOT_SIZE][][][];
        retainedBytes += ROOT_BYTES;
      } catch (OutOfMemoryError failure) {
        rollback(ROOT_BYTES);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    int branch = page >>> (DIRECTORY_BITS * 2);
    int directory = (page >>> DIRECTORY_BITS) & DIRECTORY_MASK;
    int leaf = page & DIRECTORY_MASK;
    if (root[branch] == null) {
      StatusCode status = reserve(DIRECTORY_BYTES);
      if (!status.isOk()) return status;
      try {
        root[branch] = new long[DIRECTORY_SIZE][][];
        retainedBytes += DIRECTORY_BYTES;
      } catch (OutOfMemoryError failure) {
        rollback(DIRECTORY_BYTES);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    if (root[branch][directory] == null) {
      StatusCode status = reserve(DIRECTORY_BYTES);
      if (!status.isOk()) return status;
      try {
        root[branch][directory] = new long[DIRECTORY_SIZE][];
        retainedBytes += DIRECTORY_BYTES;
      } catch (OutOfMemoryError failure) {
        rollback(DIRECTORY_BYTES);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    if (root[branch][directory][leaf] != null) return StatusCode.OK;
    StatusCode status = reserve(PAGE_BYTES);
    if (!status.isOk()) return status;
    try {
      root[branch][directory][leaf] = new long[PAGE_SIZE];
      retainedBytes += PAGE_BYTES;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      rollback(PAGE_BYTES);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserve(long bytes) {
    return budget == null ? StatusCode.OK : budget.reserve(bytes);
  }

  private void rollback(long bytes) {
    if (budget != null) budget.rollback(bytes);
  }

  private long[] page(int index) {
    int page = index >>> PAGE_SHIFT;
    return root[page >>> (DIRECTORY_BITS * 2)]
        [(page >>> DIRECTORY_BITS) & DIRECTORY_MASK][page & DIRECTORY_MASK];
  }
}
