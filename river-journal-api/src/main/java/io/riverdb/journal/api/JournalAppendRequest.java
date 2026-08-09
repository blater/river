package io.riverdb.journal.api;

import io.riverdb.journal.api.outcome.TransactionDecision;

/** Metadata for payload already encoded into a reservation's provider-owned storage. */
public final class JournalAppendRequest {
  private int formatId;
  private int formatVersion;
  private long transactionId;
  private long commitSequence;
  private TransactionDecision transactionDecision = TransactionDecision.NONE;

  public JournalAppendRequest set(
      int entryFormatId,
      int entryFormatVersion,
      long transaction,
      long csn,
      TransactionDecision decision) {
    formatId = entryFormatId;
    formatVersion = entryFormatVersion;
    transactionId = transaction;
    commitSequence = csn;
    transactionDecision = decision;
    return this;
  }

  public int formatId() {
    return formatId;
  }

  public int formatVersion() {
    return formatVersion;
  }

  public long transactionId() {
    return transactionId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public TransactionDecision transactionDecision() {
    return transactionDecision;
  }
}
