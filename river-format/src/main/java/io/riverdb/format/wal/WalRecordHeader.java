package io.riverdb.format.wal;

/** Caller-owned decoded WAL record metadata. */
public final class WalRecordHeader {
  private int totalBytes;
  private int payloadBytes;
  private int formatId;
  private int formatVersion;
  private long journalSequence;
  private long transactionId;
  private long commitSequence;
  private int decisionCode;

  public int totalBytes() {
    return totalBytes;
  }

  public int payloadBytes() {
    return payloadBytes;
  }

  public int formatId() {
    return formatId;
  }

  public int formatVersion() {
    return formatVersion;
  }

  public long journalSequence() {
    return journalSequence;
  }

  public long transactionId() {
    return transactionId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public int decisionCode() {
    return decisionCode;
  }

  public void set(
      int recordBytes,
      int bodyBytes,
      int entryFormatId,
      int entryFormatVersion,
      long sequence,
      long transaction,
      long commit,
      int decision) {
    totalBytes = recordBytes;
    payloadBytes = bodyBytes;
    formatId = entryFormatId;
    formatVersion = entryFormatVersion;
    journalSequence = sequence;
    transactionId = transaction;
    commitSequence = commit;
    decisionCode = decision;
  }

  public void reset() {
    set(0, 0, 0, 0, 0, 0, 0, 0);
  }
}
