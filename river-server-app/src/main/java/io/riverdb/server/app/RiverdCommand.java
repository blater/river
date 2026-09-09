package io.riverdb.server.app;

/** Server operations and the side-effect-free help route. */
public enum RiverdCommand {
  HELP,
  VERSION,
  START,
  STOP,
  PS,
  CREDENTIALS_RENEW_UNAVAILABLE
}
