package io.riverdb.tx.api.lock;

/** Namespace for opaque logical resource identities supplied by access methods. */
public enum LockScope {
  ROW,
  KEY,
  RANGE,
  SCHEMA
}
