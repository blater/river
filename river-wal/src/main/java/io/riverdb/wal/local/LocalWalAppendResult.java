package io.riverdb.wal.local;

/** Caller-owned local byte range and logical sequence of a forced WAL record. */
public final class LocalWalAppendResult {
  private long startOffset;
  private long endOffset;
  private long journalSequence;

  public long startOffset() {
    return startOffset;
  }

  public long endOffset() {
    return endOffset;
  }

  public long journalSequence() {
    return journalSequence;
  }

  public void set(long start, long end, long sequence) {
    startOffset = start;
    endOffset = end;
    journalSequence = sequence;
  }

  public void reset() {
    set(0, 0, 0);
  }
}
