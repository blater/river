package io.riverdb.testkit.journal;

/** Stable scan result used to resolve an indeterminate force after provider reopen. */
public enum UnknownRecoveryResolution {
  DURABLE,
  NOT_DURABLE
}
