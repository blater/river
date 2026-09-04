package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/** Reusable authenticated receipt for one aggregate transaction resource vector. */
public final class ResourceLease {
  private static final int EMPTY = 0;
  private static final int INITIAL_WAIT = 1;
  private static final int ACTIVE = 2;

  private DatabaseResourceGovernor governor;
  private long databaseToken;
  private long leaseToken;
  private long ownerId;
  private long ownerGeneration;
  private long accountedBytes;
  private long writeEntries;
  private long stagedPages;
  private long versionOperations;
  private long walBytes;
  private volatile int state;

  public boolean active() { return state == ACTIVE; }
  public boolean waiting() { return state == INITIAL_WAIT; }
  public synchronized long retryTicket() { return waiting() ? leaseToken : 0; }
  public synchronized long ownerId() { return ownerId; }
  public synchronized long ownerGeneration() { return ownerGeneration; }
  public synchronized long accountedBytes() { return accountedBytes; }
  public synchronized long writeEntries() { return writeEntries; }
  public synchronized long stagedPages() { return stagedPages; }
  public synchronized long versionOperations() { return versionOperations; }
  public synchronized long walBytes() { return walBytes; }

  public synchronized StatusCode reset() {
    if (state != EMPTY) return StatusCode.CONFLICT;
    clear();
    return StatusCode.OK;
  }

  synchronized boolean empty() { return state == EMPTY; }
  synchronized boolean initialWaiting() { return state == INITIAL_WAIT; }

  synchronized StatusCode claimInitial(
      DatabaseResourceGovernor owner, long database, long token,
      long resourceOwner, long generation, ResourceDemand demand,
      boolean activate) {
    if (state != EMPTY) return StatusCode.CONFLICT;
    governor = owner;
    databaseToken = database;
    leaseToken = token;
    ownerId = resourceOwner;
    ownerGeneration = generation;
    copy(demand);
    state = activate ? ACTIVE : INITIAL_WAIT;
    return StatusCode.OK;
  }

  synchronized StatusCode activateInitial() {
    if (state != INITIAL_WAIT) return StatusCode.CONFLICT;
    state = ACTIVE;
    return StatusCode.OK;
  }

  synchronized StatusCode grow(ResourceDemand total) {
    if (state != ACTIVE || !monotonic(total)) return StatusCode.CONFLICT;
    copy(total);
    return StatusCode.OK;
  }

  synchronized boolean matches(
      DatabaseResourceGovernor owner, long database, long resourceOwner, long generation) {
    return state == ACTIVE && governor == owner && databaseToken == database && leaseToken > 0
        && ownerId == resourceOwner && ownerGeneration == generation;
  }

  synchronized boolean matchesWaiting(
      DatabaseResourceGovernor owner, long database,
      long resourceOwner, long generation) {
    return state == INITIAL_WAIT && governor == owner && databaseToken == database
        && leaseToken > 0 && ownerId == resourceOwner && ownerGeneration == generation;
  }

  synchronized boolean matchesTarget(ResourceDemand demand) {
    return demand != null && accountedBytes == demand.accountedBytes()
        && writeEntries == demand.writeEntries()
        && stagedPages == demand.stagedPages()
        && versionOperations == demand.versionOperations()
        && walBytes == demand.walBytes();
  }

  synchronized boolean monotonic(ResourceDemand demand) {
    return demand != null && demand.accountedBytes() >= accountedBytes
        && demand.writeEntries() >= writeEntries
        && demand.stagedPages() >= stagedPages
        && demand.versionOperations() >= versionOperations
        && demand.walBytes() >= walBytes;
  }

  synchronized void complete() { clear(); }

  private void copy(ResourceDemand demand) {
    accountedBytes = demand.accountedBytes();
    writeEntries = demand.writeEntries();
    stagedPages = demand.stagedPages();
    versionOperations = demand.versionOperations();
    walBytes = demand.walBytes();
  }

  private void clear() {
    state = EMPTY;
    governor = null;
    databaseToken = leaseToken = ownerId = ownerGeneration = 0;
    accountedBytes = writeEntries = stagedPages = versionOperations = walBytes = 0;
  }
}
