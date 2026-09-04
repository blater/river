package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Authenticated receipt held only by the indexed store owning one provider set. */
public final class DatabaseStoreLease {
  private DatabaseProviderLease providers;
  private long storeToken;

  synchronized StatusCode claim(DatabaseProviderLease owner, long token) {
    if (owner == null || token <= 0 || providers != null) return StatusCode.CONFLICT;
    providers = owner;
    storeToken = token;
    return StatusCode.OK;
  }

  synchronized boolean matches(DatabaseProviderLease owner, long token) {
    return providers == owner && storeToken == token && token > 0;
  }

  synchronized void complete() {
    providers = null;
    storeToken = 0;
  }
}
