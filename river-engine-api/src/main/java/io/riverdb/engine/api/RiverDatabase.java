package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/**
 * Public database lifecycle boundary shared by embedded and remote engines.
 * Close returns CONFLICT while an API session remains open.
 */
public interface RiverDatabase {
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
