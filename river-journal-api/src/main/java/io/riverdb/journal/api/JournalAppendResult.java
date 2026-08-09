package io.riverdb.journal.api;

/** Caller-owned append output keeping logical position and local physical bytes separate. */
public final class JournalAppendResult {
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long sequence;
  private long walGeneration;
  private long recordStartLsn;
  private long recordEndLsnExclusive;
  private boolean duplicate;

  public JournalAppendResult reset() {
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    journalGeneration = 0;
    sequence = 0;
    walGeneration = 0;
    recordStartLsn = 0;
    recordEndLsnExclusive = 0;
    duplicate = false;
    return this;
  }

  /** Provider-only population hook. */
  public JournalAppendResult set(
      long databaseHigh,
      long databaseLow,
      long logicalGeneration,
      long logicalSequence,
      long localWalGeneration,
      long localRecordStartLsn,
      long localRecordEndLsnExclusive,
      boolean wasDuplicate) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    journalGeneration = logicalGeneration;
    sequence = logicalSequence;
    walGeneration = localWalGeneration;
    recordStartLsn = localRecordStartLsn;
    recordEndLsnExclusive = localRecordEndLsnExclusive;
    duplicate = wasDuplicate;
    return this;
  }

  public long databaseIncarnationHigh() {
    return databaseIncarnationHigh;
  }

  public long databaseIncarnationLow() {
    return databaseIncarnationLow;
  }

  public long journalGeneration() {
    return journalGeneration;
  }

  public long sequence() {
    return sequence;
  }

  public long walGeneration() {
    return walGeneration;
  }

  public long recordStartLsn() {
    return recordStartLsn;
  }

  public long recordEndLsnExclusive() {
    return recordEndLsnExclusive;
  }

  public boolean isDuplicate() {
    return duplicate;
  }
}
