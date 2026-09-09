package io.riverdb.server.app;

/** Server operations and the side-effect-free help route. */
public enum RiverdCommand {
  HELP,
  VERSION,
  START,
  STOP_UNAVAILABLE,
  PS_UNAVAILABLE,
  CREDENTIALS_RENEW_UNAVAILABLE
}
