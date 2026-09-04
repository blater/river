package io.riverdb.engine.table;

/** Primary reason a transaction fails the indexed group-commit predicate. */
public enum IndexedGroupCommitIneligibilityReason {
  NOT_ACTIVE,
  ACTIVE_SCAN,
  NO_SUPPORTED_HYBRID_WORK,
  TUPLE_LIFECYCLE,
  LOCK_CONFLICT
}
