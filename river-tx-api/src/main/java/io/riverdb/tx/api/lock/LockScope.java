package io.riverdb.tx.api.lock;

/** Namespace for logical resource identities supplied by access methods. */
public enum LockScope {
  ROW,
  /** Exact ordered key identified by an integer key space and a signed scalar. */
  KEY,
  /** Half-open interval between two lexicographically ordered key-space/scalar endpoints. */
  RANGE,
  SCHEMA
}
