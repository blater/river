package io.riverdb.tx.spi;

import io.riverdb.tx.api.TransactionState;

/**
 * Caller-owned checkpoint/recovery carrier. Journal lineage is represented by primitive words;
 * the physical WAL codec and durable transaction-table layout remain outside this module.
 */
public final class RecoveryTransactionView {
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long transactionId;
  private TransactionState state = TransactionState.ACTIVE;
  private long lastRecordGeneration;
  private long lastRecordLsn;
  private long undoNextGeneration;
  private long undoNextLsn;
  private long commitSequence;
  private boolean available;

  public RecoveryTransactionView reset() {
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    transactionId = 0;
    state = TransactionState.ACTIVE;
    lastRecordGeneration = 0;
    lastRecordLsn = 0;
    undoNextGeneration = 0;
    undoNextLsn = 0;
    commitSequence = 0;
    available = false;
    return this;
  }

  /** Provider/checkpoint population hook; no durable encoding is implied by field order. */
  public RecoveryTransactionView set(
      long databaseHigh,
      long databaseLow,
      long id,
      TransactionState transactionState,
      long lastGeneration,
      long lastLsn,
      long nextUndoGeneration,
      long nextUndoLsn,
      long committedAt) {
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    transactionId = id;
    state = transactionState;
    lastRecordGeneration = lastGeneration;
    lastRecordLsn = lastLsn;
    undoNextGeneration = nextUndoGeneration;
    undoNextLsn = nextUndoLsn;
    commitSequence = committedAt;
    available = true;
    return this;
  }

  public long databaseIncarnationHigh() {
    return databaseIncarnationHigh;
  }

  public long databaseIncarnationLow() {
    return databaseIncarnationLow;
  }

  public long transactionId() {
    return transactionId;
  }

  public TransactionState state() {
    return state;
  }

  public long lastRecordGeneration() {
    return lastRecordGeneration;
  }

  public long lastRecordLsn() {
    return lastRecordLsn;
  }

  public long undoNextGeneration() {
    return undoNextGeneration;
  }

  public long undoNextLsn() {
    return undoNextLsn;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public boolean isAvailable() {
    return available;
  }
}
