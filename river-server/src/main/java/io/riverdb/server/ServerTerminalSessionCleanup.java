package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
/** Transfers a disconnected endpoint's terminal ownership exactly once. */
final class ServerTerminalSessionCleanup {
  private ServerTerminalSessionCleanup() { }

  static StatusCode complete(SessionEndpoint endpoint) {
    return endpoint.close();
  }
}
