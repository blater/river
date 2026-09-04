package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Authenticated database-lifetime reservation shared by one physical provider set. */
public final class DatabaseProviderLease {
  private DatabaseResourceGovernor governor;
  private long databaseToken;
  private long retainedBytes;
  private long databaseHigh;
  private long databaseLow;
  private long walGeneration;
  private long nextStoreToken = 1;
  private DatabaseStoreLease storeLease;

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

  public synchronized StatusCode claimStore(
      long incarnationHigh,
      long incarnationLow,
      long generation,
      DatabaseStoreLease lease) {
    if (!active()) return StatusCode.NOT_OWNER;
    if (incarnationHigh == 0 && incarnationLow == 0 || generation <= 0 || lease == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (storeLease != null) return StatusCode.CONFLICT;
    if (databaseHigh != 0 || databaseLow != 0 || walGeneration != 0) {
      if (databaseHigh != incarnationHigh || databaseLow != incarnationLow
          || walGeneration != generation) return StatusCode.NOT_OWNER;
    }
    if (nextStoreToken <= 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = lease.claim(this, nextStoreToken);
    if (!status.isOk()) return status;
    databaseHigh = incarnationHigh;
    databaseLow = incarnationLow;
    walGeneration = generation;
    nextStoreToken++;
    storeLease = lease;
    return StatusCode.OK;
  }

  public synchronized StatusCode releaseStore(DatabaseStoreLease lease) {
    if (!active() || lease == null || lease != storeLease
        || !lease.matches(this, nextStoreToken - 1)) return StatusCode.NOT_OWNER;
    storeLease = null;
    lease.complete();
    return StatusCode.OK;
  }

  public synchronized boolean storeClaimed() { return storeLease != null; }
  public synchronized boolean active() { return governor != null; }
  public synchronized DatabaseResourcePlan plan() {
    return governor == null ? null : governor.plan();
  }
  public synchronized DatabaseResourceGovernor governor() { return governor; }

  synchronized boolean matches(
      DatabaseResourceGovernor owner, long token) {
    return governor == owner && databaseToken == token && retainedBytes > 0;
  }

  synchronized long retainedBytes() { return retainedBytes; }

  synchronized void complete() {
    governor = null;
    databaseToken = retainedBytes = 0;
    databaseHigh = databaseLow = walGeneration = 0;
    nextStoreToken = 1;
    if (storeLease != null) storeLease.complete();
    storeLease = null;
  }
}
