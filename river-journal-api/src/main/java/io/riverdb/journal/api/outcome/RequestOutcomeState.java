package io.riverdb.journal.api.outcome;

/** Inspectable idempotent request lifecycle without interpreting absence as failure. */
public enum RequestOutcomeState {
  NOT_FOUND,
  RESERVED,
  PUBLISHED,
  DECIDED,
  DURABLE,
  UNKNOWN
}
