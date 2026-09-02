package io.riverdb.tx.api.lock;

/** Namespace for logical resource identities supplied by access methods. */
public enum LockScope {
  ROW,
  /** Exact ordered key identified by a non-negative long key space and signed scalar. */
  KEY,
  /** Half-open interval between two lexicographically ordered key-space/scalar endpoints. */
  RANGE,
  /** Exact physical key in an unsigned lexicographic byte namespace. */
  TUPLE_KEY,
  /** Prefix-aware interval in an unsigned lexicographic byte namespace. */
  TUPLE_RANGE,
  SCHEMA
}
