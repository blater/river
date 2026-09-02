package io.riverdb.engine.api;

/** Transaction isolation requested through River's public session boundary. */
public enum IsolationLevel {
  READ_COMMITTED,
  REPEATABLE_READ,
  SERIALIZABLE
}
