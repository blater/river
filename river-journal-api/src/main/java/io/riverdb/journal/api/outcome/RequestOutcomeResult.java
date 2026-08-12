package io.riverdb.journal.api.outcome;

import io.riverdb.base.id.CommitSequence;
import io.riverdb.base.id.IdempotencyKey;
import io.riverdb.base.id.RequestId;
import io.riverdb.base.id.TransactionId;
import io.riverdb.journal.api.durability.DurabilityRequirement;

/** Caller-owned request/idempotency lookup result. */
public final class RequestOutcomeResult {
  private RequestOutcomeState state = RequestOutcomeState.NOT_FOUND;
  private RequestId requestId = RequestId.NONE;
  private IdempotencyKey idempotencyKey = IdempotencyKey.NONE;
  private long databaseIncarnationHigh;
  private long databaseIncarnationLow;
  private long journalGeneration;
  private long sequence;
  private TransactionId transactionId = TransactionId.NONE;
  private CommitSequence commitSequence = CommitSequence.NONE;
  private TransactionDecision decision = TransactionDecision.NONE;
  private DurabilityRequirement durabilityRequirement = DurabilityRequirement.LOCAL_DURABLE;
  private boolean finalOutcome;

  public RequestOutcomeResult reset() {
    state = RequestOutcomeState.NOT_FOUND;
    requestId = RequestId.NONE;
    idempotencyKey = IdempotencyKey.NONE;
    databaseIncarnationHigh = 0;
    databaseIncarnationLow = 0;
    journalGeneration = 0;
    sequence = 0;
    transactionId = TransactionId.NONE;
    commitSequence = CommitSequence.NONE;
    decision = TransactionDecision.NONE;
    durabilityRequirement = DurabilityRequirement.LOCAL_DURABLE;
    finalOutcome = false;
    return this;
  }

  /** Provider-only population hook. */
  public RequestOutcomeResult set(
      RequestOutcomeState outcomeState,
      RequestId request,
      IdempotencyKey key,
      long databaseHigh,
      long databaseLow,
      long logicalGeneration,
      long logicalSequence,
      TransactionId transaction,
      CommitSequence csn,
      TransactionDecision transactionDecision,
      DurabilityRequirement durability,
      boolean isFinal) {
    state = outcomeState;
    requestId = request;
    idempotencyKey = key;
    databaseIncarnationHigh = databaseHigh;
    databaseIncarnationLow = databaseLow;
    journalGeneration = logicalGeneration;
    sequence = logicalSequence;
    transactionId = transaction;
    commitSequence = csn;
    decision = transactionDecision;
    durabilityRequirement = durability;
    finalOutcome = isFinal;
    return this;
  }

  public RequestOutcomeState state() {
    return state;
  }

  public RequestId requestId() {
    return requestId;
  }

  public IdempotencyKey idempotencyKey() {
    return idempotencyKey;
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

  public TransactionId transactionId() {
    return transactionId;
  }

  public CommitSequence commitSequence() {
    return commitSequence;
  }

  public TransactionDecision decision() {
    return decision;
  }

  public DurabilityRequirement durabilityRequirement() {
    return durabilityRequirement;
  }

  public boolean isFinal() {
    return finalOutcome;
  }
}
