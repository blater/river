package io.riverdb.tx.api;

/** Transaction lifecycle state; storage providers enforce the legal transition graph. */
public enum TransactionState {
  ACTIVE,
  PREPARED,
  COMMITTING,
  COMMITTED,
  ABORTING,
  ABORTED,
  INDETERMINATE
}
