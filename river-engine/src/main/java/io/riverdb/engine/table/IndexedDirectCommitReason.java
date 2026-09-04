package io.riverdb.engine.table;

/** Mutually exclusive reason a transaction takes the direct commit path. */
public enum IndexedDirectCommitReason {
  INITIALLY_INELIGIBLE,
  EXPLICIT_DIRECT_PATH,
  OTHER
}
