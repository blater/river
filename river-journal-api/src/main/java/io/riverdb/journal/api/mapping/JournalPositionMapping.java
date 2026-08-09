package io.riverdb.journal.api.mapping;

import io.riverdb.base.id.WalGeneration;

/** Caller-owned inspection result keeping logical, physical, and visibility units distinct. */
public final class JournalPositionMapping {
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long sequence;
  private WalGeneration walGeneration = WalGeneration.NONE;
  private long recordStartLsn;
  private long recordEndLsnExclusive;
  private long transactionId;
  private long commitSequence;
  private boolean transactionDecision;

  public JournalPositionMapping reset() {
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    journalGeneration = 0;
    sequence = 0;
    walGeneration = WalGeneration.NONE;
    recordStartLsn = 0;
    recordEndLsnExclusive = 0;
    transactionId = 0;
    commitSequence = 0;
    transactionDecision = false;
    return this;
  }

  /** Provider-only population hook; callers should treat this object as output storage. */
  public JournalPositionMapping set(
      long databaseHigh,
      long databaseLow,
      long logicalGeneration,
      long logicalSequence,
      WalGeneration localWalGeneration,
      long localRecordStartLsn,
      long localRecordEndLsnExclusive,
      long transaction,
      long csn,
      boolean isTransactionDecision) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    journalGeneration = logicalGeneration;
    sequence = logicalSequence;
    walGeneration = localWalGeneration;
    recordStartLsn = localRecordStartLsn;
    recordEndLsnExclusive = localRecordEndLsnExclusive;
    transactionId = transaction;
    commitSequence = csn;
    transactionDecision = isTransactionDecision;
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

  public WalGeneration walGeneration() {
    return walGeneration;
  }

  public long recordStartLsn() {
    return recordStartLsn;
  }

  public long recordEndLsnExclusive() {
    return recordEndLsnExclusive;
  }

  public long transactionId() {
    return transactionId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public boolean isTransactionDecision() {
    return transactionDecision;
  }
}
