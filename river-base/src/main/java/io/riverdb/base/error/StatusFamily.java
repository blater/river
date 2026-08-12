package io.riverdb.base.error;

/** Stable, coarse outcome classes used for policy and public-boundary mapping. */
public enum StatusFamily {
  OK,
  RETRY,
  CANCELLED,
  INVALID_EXTERNAL_INPUT,
  CONFLICT,
  RESOURCE_EXHAUSTED,
  TIMEOUT,
  IO_FAILURE,
  CORRUPTION,
  INVARIANT_BROKEN
}
