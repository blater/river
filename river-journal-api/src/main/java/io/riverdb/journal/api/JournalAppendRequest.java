package io.riverdb.journal.api;

import io.riverdb.journal.api.outcome.TransactionDecision;
import java.nio.ByteBuffer;

/** Borrowed immutable payload view and optional transaction-decision metadata. */
public final class JournalAppendRequest {
  private ByteBuffer payload;
  private int formatId;
  private int formatVersion;
  private long transactionId;
  private long commitSequence;
  private TransactionDecision transactionDecision = TransactionDecision.NONE;

  public JournalAppendRequest set(
      ByteBuffer payloadView,
      int entryFormatId,
      int entryFormatVersion,
      long transaction,
      long csn,
      TransactionDecision decision) {
    payload = payloadView;
    formatId = entryFormatId;
    formatVersion = entryFormatVersion;
    transactionId = transaction;
    commitSequence = csn;
    transactionDecision = decision;
    return this;
  }

  public ByteBuffer payload() {
    return payload;
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
