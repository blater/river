package io.riverdb.engine.sql;

/** Two-phase release of inactive, exactly charged session storage under budget pressure. */
interface SqlRetainedReclaimer {
  long reclaimableRetainedBytes();
  void releaseRetainedStorage();
}
