package io.riverdb.server.app;

/** Commands understood by the first installed riverd command-line composition. */
public enum RiverdCommand {
  BRIEF_HELP,
  FULL_HELP,
  VERSION,
  START,
  STOP_UNAVAILABLE,
  PS_UNAVAILABLE,
  AUDIT_ARCHIVE_UNAVAILABLE,
  CREDENTIALS_RENEW_UNAVAILABLE
}
