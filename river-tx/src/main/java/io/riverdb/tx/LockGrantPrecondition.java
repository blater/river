package io.riverdb.tx;

/** Grant predicate whose false result keeps a lock request from advancing. */
public enum LockGrantPrecondition {
  NO_INCOMPATIBLE_ACTIVE_OWNER,
  CONVERSION_QUEUE_EMPTY,
  FIFO_QUEUE_HEAD,
  NO_EARLIER_INCOMPATIBLE_WAITER
}
