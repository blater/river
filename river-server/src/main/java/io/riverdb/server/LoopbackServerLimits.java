package io.riverdb.server;

import io.riverdb.protocol.ProtocolMemoryBudget;

/** Bounded connection and transport timeout limits for one server. */
public record LoopbackServerLimits(
    int maximumConnections,
    int authenticationTimeoutMillis,
    int idleTimeoutMillis) {
  public static final int DEFAULT_AUTHENTICATION_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000;

  public static LoopbackServerLimits defaults(int maximumConnections) {
    return new LoopbackServerLimits(
        maximumConnections,
        DEFAULT_AUTHENTICATION_TIMEOUT_MILLIS,
        DEFAULT_IDLE_TIMEOUT_MILLIS);
  }

  boolean isValid() {
    return maximumConnections > 0
        && ProtocolMemoryBudget.supportsServerConnections(maximumConnections)
        && authenticationTimeoutMillis > 0
        && idleTimeoutMillis > 0;
  }
}
