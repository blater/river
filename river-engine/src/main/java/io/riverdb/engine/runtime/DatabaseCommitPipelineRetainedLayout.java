package io.riverdb.engine.runtime;

/** Checked retained-byte model for one database-local commit and WAL-force pipeline. */
public final class DatabaseCommitPipelineRetainedLayout {
  private static final long ARRAY_HEADER_BYTES = 16;
  private static final long RING_OWNER_BYTES = 112;
  private static final long FORCE_WORKER_BYTES = 4_096;

  private DatabaseCommitPipelineRetainedLayout() {}

  public static long retainedBytes(int maximumActiveTransactions) {
    if (maximumActiveTransactions <= 0) return -1;
    long wide = arrayBytes(maximumActiveTransactions, Long.BYTES);
    long narrow = arrayBytes(maximumActiveTransactions, Integer.BYTES);
    long total = multiply(wide, 5);
    total = add(total, multiply(narrow, 3));
    total = add(total, RING_OWNER_BYTES);
    return add(total, FORCE_WORKER_BYTES);
  }

  private static long arrayBytes(int entries, long elementBytes) {
    long payload = multiply(entries, elementBytes);
    return payload < 0 ? -1 : align(add(ARRAY_HEADER_BYTES, payload));
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }

  private static long multiply(long value, long count) {
    return value < 0 || count < 0 || value != 0 && count > Long.MAX_VALUE / value
        ? -1 : value * count;
  }

  private static long align(long bytes) {
    return bytes < 0 || bytes > Long.MAX_VALUE - 7 ? -1 : (bytes + 7) & ~7L;
  }
}
