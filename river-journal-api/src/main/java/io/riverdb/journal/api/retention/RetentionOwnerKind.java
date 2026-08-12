package io.riverdb.journal.api.retention;

/** Semantic consumer responsible for a bounded WAL retention lease. */
public enum RetentionOwnerKind {
  RECOVERY,
  BACKUP,
  CDC,
  UPGRADE,
  REPLICA_CATCH_UP
}
