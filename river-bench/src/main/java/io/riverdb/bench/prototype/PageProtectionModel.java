package io.riverdb.bench.prototype;

/** Deterministic byte/force/copy model, not a recovery implementation. */
public final class PageProtectionModel {
  private final int pageCount;
  private final int pageSize;
  private final int deltaBytes;
  private final int groupSize;
  private final boolean[] dirtied;

  public PageProtectionModel(
      int pageCount,
      int pageSize,
      int deltaBytes,
      int groupSize
  ) {
    this.pageCount = pageCount;
    this.pageSize = pageSize;
    this.deltaBytes = deltaBytes;
    this.groupSize = groupSize;
    dirtied = new boolean[pageCount];
  }

  public void firstPageImage(int dirtyOperations, long seed, PageProtectionResult out) {
    reset(out);
    clearDirtied();
    long state = seed;
    for (int operation = 0; operation < dirtyOperations; operation++) {
      state = next(state);
      int page = (int) Long.remainderUnsigned(state, pageCount);
      if (!dirtied[page]) {
        dirtied[page] = true;
        out.walBytes += pageSize;
        out.copiedBytes += pageSize;
        out.firstDirtyPages++;
      } else {
        out.redirties++;
      }
      out.walBytes += deltaBytes;
      out.dirties++;
    }
    out.dataBytes = out.firstDirtyPages * pageSize;
    // FPI records share WAL group commits; checkpoint pages need one data force.
    out.walForceCalls = divideRoundUp(dirtyOperations, groupSize);
    out.dataForceCalls = out.firstDirtyPages == 0L ? 0L : 1L;
  }

  public void doubleWrite(int dirtyOperations, long seed, PageProtectionResult out) {
    reset(out);
    clearDirtied();
    long state = seed;
    for (int operation = 0; operation < dirtyOperations; operation++) {
      state = next(state);
      int page = (int) Long.remainderUnsigned(state, pageCount);
      if (!dirtied[page]) {
        dirtied[page] = true;
        out.firstDirtyPages++;
      } else {
        out.redirties++;
      }
      out.walBytes += deltaBytes;
      out.dirties++;
    }
    long pageBytes = out.firstDirtyPages * pageSize;
    out.stagingBytes = pageBytes;
    out.dataBytes = pageBytes;
    out.copiedBytes = pageBytes;
    // Staging must be stable before checkpoint pages reach their home positions.
    out.walForceCalls = divideRoundUp(dirtyOperations, groupSize);
    out.stagingForceCalls = divideRoundUp(out.firstDirtyPages, groupSize);
    out.dataForceCalls = out.firstDirtyPages == 0L ? 0L : 1L;
  }

  private void clearDirtied() {
    for (int index = 0; index < dirtied.length; index++) {
      dirtied[index] = false;
    }
  }

  private static void reset(PageProtectionResult out) {
    out.walBytes = 0L;
    out.stagingBytes = 0L;
    out.dataBytes = 0L;
    out.copiedBytes = 0L;
    out.walForceCalls = 0L;
    out.stagingForceCalls = 0L;
    out.dataForceCalls = 0L;
    out.dirties = 0L;
    out.firstDirtyPages = 0L;
    out.redirties = 0L;
  }

  private static long next(long state) {
    long value = state + 0x9E3779B97F4A7C15L;
    value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
    value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
    return value ^ value >>> 31;
  }

  private static long divideRoundUp(long value, long divisor) {
    return (value + divisor - 1L) / divisor;
  }
}
