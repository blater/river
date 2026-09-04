package io.riverdb.wal.local;

/** Mutually exclusive reason for a physical local WAL force. */
public enum LocalWalForceCause {
  SHARED_GROUP,
  DIRECT_COMMIT,
  CHECKPOINT,
  RECOVERY_MAINTENANCE,
  OTHER
}
