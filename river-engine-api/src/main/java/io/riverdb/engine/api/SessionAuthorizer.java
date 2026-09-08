package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Authorizes one already-bound principal's durable statement admission. */
@FunctionalInterface
public interface SessionAuthorizer {
  StatusCode authorize(int requiredPermission, int phase, int programStep);
}
