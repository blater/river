package io.riverdb.journal.api.outcome;

/** Decision carried by a versioned transaction journal entry. */
public enum TransactionDecision {
  NONE,
  COMMITTED,
  ABORTED
}
