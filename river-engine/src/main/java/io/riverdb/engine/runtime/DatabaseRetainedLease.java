package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Authenticated absolute high-water receipt for one database-owned retained component. */
public final class DatabaseRetainedLease {
  private DatabaseResourceGovernor governor;
  private long databaseToken;
  private long retainedBytes;

  public synchronized boolean active() { return governor != null; }
  public synchronized long retainedBytes() { return retainedBytes; }

  synchronized StatusCode claim(
      DatabaseResourceGovernor owner, long token, long bytes) {
    if (owner == null || token <= 0 || bytes <= 0 || active()) {
      return StatusCode.CONFLICT;
    }
    governor = owner;
    databaseToken = token;
    retainedBytes = bytes;
    return StatusCode.OK;
  }

  synchronized StatusCode grow(long bytes) {
    if (!active() || bytes < retainedBytes) return StatusCode.CONFLICT;
    retainedBytes = bytes;
    return StatusCode.OK;
  }

  synchronized boolean matches(
      DatabaseResourceGovernor owner, long token) {
    return governor == owner && databaseToken == token && retainedBytes > 0;
  }

  synchronized void complete() {
    governor = null;
    databaseToken = retainedBytes = 0;
  }
}
