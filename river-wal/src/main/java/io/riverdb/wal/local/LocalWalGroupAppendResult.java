package io.riverdb.wal.local;

/** Caller-owned byte range and first sequence for one appended logical record group. */
public final class LocalWalGroupAppendResult {
  private long startOffset;
  private long endOffset;
  private long firstJournalSequence;
  private int recordCount;

  public long startOffset() { return startOffset; }
  public long endOffset() { return endOffset; }
  public long firstJournalSequence() { return firstJournalSequence; }
  public int recordCount() { return recordCount; }
  public void reset() { set(0, 0, 0, 0); }
  void set(long start, long end, long sequence, int count) {
    startOffset = start;
    endOffset = end;
    firstJournalSequence = sequence;
    recordCount = count;
  }
}
