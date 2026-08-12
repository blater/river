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
    firstPageImageEpochs(1, dirtyOperations, seed, out);
  }

  public void firstPageImageEpochs(
      int epochCount,
      int dirtyOperationsPerEpoch,
      long seed,
      PageProtectionResult out
  ) {
    reset(out);
    for (int epoch = 0; epoch < epochCount; epoch++) {
      clearDirtied();
      long state = seed ^ (epoch * 0x9E3779B97F4A7C15L);
      long epochFirstDirties = 0L;
      for (int operation = 0; operation < dirtyOperationsPerEpoch; operation++) {
        state = next(state);
        int page = (int) Long.remainderUnsigned(state, pageCount);
        if (!dirtied[page]) {
          dirtied[page] = true;
          out.walBytes += pageSize;
          out.copiedBytes += pageSize;
          out.firstDirtyPages++;
          out.immutableImageCopies++;
          out.immutableImageCopyBytes += pageSize;
          epochFirstDirties++;
        } else {
          out.redirties++;
        }
        out.walBytes += deltaBytes;
        out.dirties++;
      }
      out.dataBytes += epochFirstDirties * pageSize;
      out.walForceCalls += divideRoundUp(dirtyOperationsPerEpoch, groupSize);
      out.dataForceCalls += epochFirstDirties == 0L ? 0L : 1L;
      out.checkpointEpochs++;
    }
  }

  public void doubleWrite(int dirtyOperations, long seed, PageProtectionResult out) {
    doubleWriteEpochs(1, dirtyOperations, seed, out);
  }

  public void doubleWriteEpochs(
      int epochCount,
      int dirtyOperationsPerEpoch,
      long seed,
      PageProtectionResult out
  ) {
    reset(out);
    for (int epoch = 0; epoch < epochCount; epoch++) {
      clearDirtied();
      long state = seed ^ (epoch * 0x9E3779B97F4A7C15L);
      long epochFirstDirties = 0L;
      for (int operation = 0; operation < dirtyOperationsPerEpoch; operation++) {
        state = next(state);
        int page = (int) Long.remainderUnsigned(state, pageCount);
        if (!dirtied[page]) {
          dirtied[page] = true;
          out.firstDirtyPages++;
          epochFirstDirties++;
        } else {
          out.redirties++;
        }
        out.walBytes += deltaBytes;
        out.dirties++;
      }
      long pageBytes = epochFirstDirties * pageSize;
      out.stagingBytes += pageBytes;
      out.dataBytes += pageBytes;
      out.copiedBytes += pageBytes;
      out.stagingCopies += epochFirstDirties;
      out.stagingCopyBytes += pageBytes;
      out.walForceCalls += divideRoundUp(dirtyOperationsPerEpoch, groupSize);
      out.stagingForceCalls += divideRoundUp(epochFirstDirties, groupSize);
      out.dataForceCalls += epochFirstDirties == 0L ? 0L : 1L;
      out.checkpointEpochs++;
    }
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
    out.checkpointEpochs = 0L;
    out.immutableImageCopies = 0L;
    out.immutableImageCopyBytes = 0L;
    out.stagingCopies = 0L;
    out.stagingCopyBytes = 0L;
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
