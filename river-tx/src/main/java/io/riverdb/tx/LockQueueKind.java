package io.riverdb.tx;

/** Canonical lock-table chain occupied by one side of a diagnostic edge. */
public enum LockQueueKind {
  NONE,
  ACTIVE_OWNER,
  CONVERSION,
  ORDINARY
}
