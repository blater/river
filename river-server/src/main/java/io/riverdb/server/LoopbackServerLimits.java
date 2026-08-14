package io.riverdb.server;

/** Bounded connection, timeout, and audit capacities for one server. */
public record LoopbackServerLimits(
    int maximumConnections,
    int authenticationTimeoutMillis,
    int idleTimeoutMillis,
    int maximumAuditRecords) {
  public static final int DEFAULT_AUTHENTICATION_TIMEOUT_MILLIS = 5_000;
  public static final int DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000;
  public static final int DEFAULT_MAXIMUM_AUDIT_RECORDS = 4_096;

  public static LoopbackServerLimits defaults(int maximumConnections) {
    return new LoopbackServerLimits(
        maximumConnections,
        DEFAULT_AUTHENTICATION_TIMEOUT_MILLIS,
        DEFAULT_IDLE_TIMEOUT_MILLIS,
        DEFAULT_MAXIMUM_AUDIT_RECORDS);
  }

  boolean isValid() {
    return maximumConnections > 0
        && maximumConnections <= LoopbackRiverServer.MAXIMUM_CONNECTION_LIMIT
        && authenticationTimeoutMillis > 0
        && authenticationTimeoutMillis <= 300_000
        && idleTimeoutMillis > 0
        && idleTimeoutMillis <= 300_000
        && maximumAuditRecords > 0
        && maximumAuditRecords <= 1_000_000;
  }
}
