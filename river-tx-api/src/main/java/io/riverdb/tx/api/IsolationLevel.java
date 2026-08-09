package io.riverdb.tx.api;

/** River isolation vocabulary, independent of JDBC constants and wire encodings. */
public enum IsolationLevel {
  READ_COMMITTED,
  REPEATABLE_READ,
  SERIALIZABLE
}
