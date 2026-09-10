package io.riverdb.format.wal;

/** Caller-owned decoded metadata for one append-only WAL commit group footer. */
public final class WalCommitGroupHeader {
  private long groupBytes;
  private long recordBytes;
  private long recordCount;
  private long firstJournalSequence;
  private long lastJournalSequence;
  private long groupStart;
  private int previousDigest;
  private int groupChecksum;

  public long groupBytes() { return groupBytes; }
  public long recordBytes() { return recordBytes; }
  public long recordCount() { return recordCount; }
  public long firstJournalSequence() { return firstJournalSequence; }
  public long lastJournalSequence() { return lastJournalSequence; }
  public long groupStart() { return groupStart; }
  public int previousDigest() { return previousDigest; }
  public int groupChecksum() { return groupChecksum; }

  void set(
      long groupSize,
      long recordsSize,
      long records,
      long firstSequence,
      long lastSequence,
      long start,
      int previous,
      int checksum) {
    groupBytes = groupSize;
    recordBytes = recordsSize;
    recordCount = records;
    firstJournalSequence = firstSequence;
    lastJournalSequence = lastSequence;
    groupStart = start;
    previousDigest = previous;
    groupChecksum = checksum;
  }

  public void reset() { set(0, 0, 0, 0, 0, 0, 0, 0); }
}
