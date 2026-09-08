package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Authorizes one already-bound principal's statement admission. */
@FunctionalInterface
public interface SessionAuthorizer {
  StatusCode authorize(int requiredPermission);
}
