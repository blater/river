package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/**
 * Public database lifecycle boundary shared by embedded and remote engines.
 * Close returns CONFLICT while an API session remains open.
 */
public interface RiverDatabase {
  /** Lock-wait counters are available for an embedded managed-server diagnostic. */
  default int activeTransactionCount() { return -1; }

  default long activeLockCount() { return -1; }

  default long waitingLockCount() { return -1; }

  default long lockWaitsEntered() { return -1; }

  default long lockWaitsActuallyBlocked() { return -1; }

  default long lockWaitBlockedNanos() { return -1; }

  default long lockWaitsGranted() { return -1; }

  default long lockWaitsTimedOut() { return -1; }

  default long lockWaitsDeadlocked() { return -1; }

  default long lockWaitsCancelled() { return -1; }

  default boolean lockEscalationSupported() { return false; }

  default long lockEscalationCount() { return -1; }

  StatusCode createSession(SessionOpenResult result);

  default StatusCode createSession(
      SessionAuthorizer authorizer, SessionOpenResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (authorizer == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.ACCESS_DENIED;
  }

  /**
   * Transfers exact ownership of an unreachable session for database-owned terminal cleanup.
   * OK means the caller must never access the session again; every failure leaves it caller-owned.
   */
  StatusCode deferTerminalClose(RiverSession session);

  StatusCode close();
}
