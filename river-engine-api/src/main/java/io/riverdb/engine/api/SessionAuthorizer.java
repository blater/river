package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Authenticates one already-bound principal's durable statement admission. */
@FunctionalInterface
public interface SessionAuthorizer {
  StatusCode authorize(int requiredPermission);
}
