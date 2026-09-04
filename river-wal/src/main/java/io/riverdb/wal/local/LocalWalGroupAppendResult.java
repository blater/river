package io.riverdb.wal.local;

/** Caller-owned byte range and first sequence for one appended logical record group. */
public final class LocalWalGroupAppendResult {
  private long startOffset;
  private long endOffset;
  private long firstJournalSequence;
  private long recordCount;
  private LocalWalAppendDisposition disposition =
      LocalWalAppendDisposition.NOTHING_WRITTEN;

  public long startOffset() { return startOffset; }
  public long endOffset() { return endOffset; }
  public long firstJournalSequence() { return firstJournalSequence; }
  public long recordCount() { return recordCount; }
  public LocalWalAppendDisposition disposition() { return disposition; }
  public void reset() {
    startOffset = endOffset = firstJournalSequence = recordCount = 0;
    disposition = LocalWalAppendDisposition.NOTHING_WRITTEN;
  }
  void markStorageMayHaveChanged() {
    if (disposition == LocalWalAppendDisposition.NOTHING_WRITTEN) {
      disposition = LocalWalAppendDisposition.STORAGE_MAY_HAVE_CHANGED;
    }
  }
  void set(long start, long end, long sequence, long count) {
    startOffset = start;
    endOffset = end;
    firstJournalSequence = sequence;
    recordCount = count;
    disposition = LocalWalAppendDisposition.COMPLETE;
  }
}
