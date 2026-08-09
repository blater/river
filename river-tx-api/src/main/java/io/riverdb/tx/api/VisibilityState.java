package io.riverdb.tx.api;

/** Storage-facing result of resolving one owning transaction against a snapshot. */
public enum VisibilityState {
  VISIBLE,
  HIDDEN,
  OWN_WRITE,
  OUTCOME_UNAVAILABLE,
  INDETERMINATE
}
