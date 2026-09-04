package io.riverdb.wal.local;

/** Caller-owned result for one forced, half-open local WAL append batch. */
public final class LocalWalForceResult {
  private long startOffset;
  private long durableEnd;
  private long commitSequence;
  private long recordCount;

  public void reset() {
    startOffset = 0;
    durableEnd = 0;
    commitSequence = 0;
    recordCount = 0;
  }

  public void set(long start, long end, long records, long committedAt) {
    startOffset = start;
    durableEnd = end;
    recordCount = records;
    commitSequence = committedAt;
  }

  public long startOffset() {
    return startOffset;
  }

  public long durableEnd() {
    return durableEnd;
  }

  public long recordCount() {
    return recordCount;
  }

  public long commitSequence() {
    return commitSequence;
  }
}
