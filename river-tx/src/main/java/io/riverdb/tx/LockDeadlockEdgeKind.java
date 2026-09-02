package io.riverdb.tx;

/** Scheduler dependency represented by one edge in a deadlock cycle. */
public enum LockDeadlockEdgeKind {
  ACTIVE_OWNER,
  CONVERSION_PRIORITY,
  FIFO_FAIRNESS
}
