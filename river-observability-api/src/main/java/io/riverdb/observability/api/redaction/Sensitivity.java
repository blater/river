package io.riverdb.observability.api.redaction;

/** Classification used by the central diagnostic export policy. */
public enum Sensitivity {
  PUBLIC,
  INTERNAL,
  SENSITIVE
}
