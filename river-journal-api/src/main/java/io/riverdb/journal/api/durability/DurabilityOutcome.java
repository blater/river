package io.riverdb.journal.api.durability;

/** Exact result of a durability wait, including indeterminate completion. */
public enum DurabilityOutcome {
  PENDING,
  SATISFIED,
  CANCELLED,
  TIMED_OUT,
  UNKNOWN,
  FENCED,
  UNSUPPORTED
}
