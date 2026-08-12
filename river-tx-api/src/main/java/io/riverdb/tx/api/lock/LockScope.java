package io.riverdb.tx.api.lock;

/** Namespace for logical resource identities supplied by access methods. */
public enum LockScope {
  ROW,
  KEY,
  /** Half-open signed key interval encoded as resourceHigh=lower, resourceLow=upper. */
  RANGE,
  SCHEMA
}
