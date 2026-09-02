package io.riverdb.engine.runtime;

import io.riverdb.base.error.StatusCode;

/**
 * Database-owned atomic admission boundary for transaction resource vectors.
 * Each transaction owner holds one aggregate lease for its generation.
 * RETRY owns a bounded FIFO ticket in the caller's lease until retry or cancellation.
 */
public final class DatabaseResourceGovernor {
  private final RuntimeResourceRoot root;
  private final DatabaseResourcePlan plan;
  private final long databaseToken;
  private final DatabaseResourceState live = new DatabaseResourceState();
  private final ResourceOwnerIndex owners;
  private final ResourceLease[] waiting;
  private int waitingHead;
  private int waitingCount;
  private long nextLeaseToken = 1;
  private long retainedDatabaseAccountedBytes;
  private boolean closed;

  DatabaseResourceGovernor(
      RuntimeResourceRoot resourceRoot, DatabaseResourcePlan resourcePlan, long token) {
    root = resourceRoot;
    plan = resourcePlan;
    databaseToken = token;
    owners = new ResourceOwnerIndex(resourcePlan.maximumOwners());
    waiting = new ResourceLease[resourcePlan.maximumOwners()];
  }

  public synchronized StatusCode reserve(
      long ownerId, long ownerGeneration, ResourceDemand demand, ResourceLease lease) {
    if (lease != null && lease.active()) return StatusCode.CONFLICT;
    return ensure(ownerId, ownerGeneration, demand, lease, true);
  }

  /** Ensures one owner's aggregate lease covers the supplied componentwise-monotonic total. */
  public synchronized StatusCode ensure(
      long ownerId,
      long ownerGeneration,
      ResourceDemand total,
      ResourceLease lease,
      boolean waitingAllowed) {
    if (ownerId <= 0 || ownerGeneration <= 0 || total == null || !total.valid()
        || lease == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (closed) return StatusCode.CLOSED;
    if (!live.everFits(plan, lendableAccountedBytes(), total)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (lease.initialWaiting()) return retryInitial(ownerId, ownerGeneration, total, lease);
    if (lease.active()) return grow(
        ownerId, ownerGeneration, total, lease);
    if (!lease.empty()) return StatusCode.CONFLICT;
    if (owners.contains(ownerId, ownerGeneration)) return StatusCode.CONFLICT;
    if (waitingCount > 0 || !live.available(plan, lendableAccountedBytes(), total)) {
      return waitingAllowed
          ? enqueueInitial(ownerId, ownerGeneration, total, lease) : StatusCode.RETRY;
    }
    if (nextLeaseToken <= 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = lease.claimInitial(
        this, databaseToken, nextLeaseToken, ownerId, ownerGeneration, total, true);
    if (status.isOk()) {
      if (!owners.add(ownerId, ownerGeneration)) {
        lease.complete();
        return StatusCode.INVARIANT_BROKEN;
      }
      nextLeaseToken++;
      live.add(total);
    }
    return status;
  }

  public synchronized StatusCode cancel(
      long ownerId, long ownerGeneration, ResourceLease lease) {
    if (lease == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int offset = waitingOffset(lease);
    if (offset < 0
        || !lease.matchesWaiting(this, databaseToken, ownerId, ownerGeneration)) {
      return StatusCode.NOT_OWNER;
    }
    removeWaiting(offset);
    if (!owners.remove(ownerId, ownerGeneration)) return StatusCode.INVARIANT_BROKEN;
    lease.complete();
    return StatusCode.OK;
  }

  public synchronized StatusCode release(
      long ownerId, long ownerGeneration, ResourceLease lease) {
    if (lease == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!lease.matches(this, databaseToken, ownerId, ownerGeneration)) {
      return StatusCode.NOT_OWNER;
    }
    if (!live.contains(lease)) return StatusCode.INVARIANT_BROKEN;
    live.remove(lease);
    if (!owners.remove(ownerId, ownerGeneration)) return StatusCode.INVARIANT_BROKEN;
    lease.complete();
    return StatusCode.OK;
  }

  /** Cancels any pending ticket and releases the owner's current vector atomically. */
  public synchronized StatusCode end(
      long ownerId, long ownerGeneration, ResourceLease lease) {
    if (lease == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (lease.initialWaiting()) return cancel(ownerId, ownerGeneration, lease);
    if (!lease.matches(this, databaseToken, ownerId, ownerGeneration)) {
      return StatusCode.NOT_OWNER;
    }
    return release(ownerId, ownerGeneration, lease);
  }

  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    if (!live.empty() || waitingCount != 0) return StatusCode.CONFLICT;
    StatusCode status = root.release(databaseToken, plan.maximumAccountedBytes());
    if (status.isOk()) closed = true;
    return status;
  }

  /** Permanently reserves database-global provider storage within this admitted envelope. */
  public synchronized StatusCode retainDatabaseAccountedBytes(long total) {
    if (closed) return StatusCode.CLOSED;
    if (total < retainedDatabaseAccountedBytes || !live.empty() || waitingCount != 0) {
      return StatusCode.CONFLICT;
    }
    if (total > maximumRetainedAccountedBytes()) return StatusCode.RESOURCE_EXHAUSTED;
    retainedDatabaseAccountedBytes = total;
    return StatusCode.OK;
  }

  /** Grows retained database-global storage before allocation; the high-water is monotonic. */
  public synchronized StatusCode growRetainedDatabaseAccountedBytes(long additional) {
    if (closed) return StatusCode.CLOSED;
    if (additional <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    long maximumRetained = maximumRetainedAccountedBytes();
    if (retainedDatabaseAccountedBytes > maximumRetained - additional) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long next = retainedDatabaseAccountedBytes + additional;
    long capacity = plan.accountedCapacityBytes();
    long nextLendable = capacity - next;
    if (live.accountedBytes > nextLendable || !waitingFits(nextLendable)) {
      return StatusCode.RETRY;
    }
    retainedDatabaseAccountedBytes = next;
    return StatusCode.OK;
  }

  public synchronized StatusCode releaseRetainedDatabaseAccountedBytes(long bytes) {
    if (closed) return StatusCode.CLOSED;
    if (bytes <= 0 || bytes > retainedDatabaseAccountedBytes) {
      return StatusCode.INVARIANT_BROKEN;
    }
    retainedDatabaseAccountedBytes -= bytes;
    return StatusCode.OK;
  }

  /** Releases an unpublished database admission after every owner has become unreachable. */
  public synchronized StatusCode abandonAfterOpenFailure() {
    if (closed) return StatusCode.CLOSED;
    for (int offset = 0; offset < waitingCount; offset++) {
      ResourceLease lease = waiting[(waitingHead + offset) % waiting.length];
      if (lease != null) lease.complete();
      waiting[(waitingHead + offset) % waiting.length] = null;
    }
    waitingHead = waitingCount = 0;
    live.reset();
    StatusCode status = root.release(databaseToken, plan.maximumAccountedBytes());
    if (status.isOk()) closed = true;
    return status;
  }

  public DatabaseResourcePlan plan() { return plan; }
  public synchronized int liveLeaseCount() { return live.leases; }
  public synchronized long liveAccountedBytes() { return live.accountedBytes; }
  public synchronized long retainedDatabaseAccountedBytes() {
    return retainedDatabaseAccountedBytes;
  }
  public synchronized long liveWriteEntries() { return live.writeEntries; }
  public synchronized long liveStagedPages() { return live.stagedPages; }
  public synchronized long liveWalBytes() { return live.walBytes; }
  public synchronized long availableAccountedBytes() {
    return lendableAccountedBytes() - live.accountedBytes;
  }
  public synchronized long availableWriteEntries() {
    return plan.writeEntryCapacity() - live.writeEntries;
  }
  public synchronized long availableStagedPages() {
    return plan.stagedPageCapacity() - live.stagedPages;
  }
  public synchronized long availableWalBytes() {
    return plan.walByteCapacity() - live.walBytes;
  }
  public synchronized int waitingLeaseCount() { return waitingCount; }

  private StatusCode retryInitial(
      long ownerId, long ownerGeneration, ResourceDemand demand, ResourceLease lease) {
    if (!lease.matchesWaiting(this, databaseToken, ownerId, ownerGeneration)
        || !lease.matchesTarget(demand)) return StatusCode.NOT_OWNER;
    if (waitingCount == 0 || waiting[waitingHead] != lease
        || !live.available(plan, lendableAccountedBytes(), demand)) return StatusCode.RETRY;
    StatusCode status = lease.activateInitial();
    if (!status.isOk()) return status;
    waiting[waitingHead] = null;
    waitingHead = (waitingHead + 1) % waiting.length;
    waitingCount--;
    live.add(lease);
    return StatusCode.OK;
  }

  private StatusCode enqueueInitial(
      long ownerId, long ownerGeneration, ResourceDemand demand, ResourceLease lease) {
    if (owners.size() >= plan.maximumOwners() || nextLeaseToken <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = lease.claimInitial(
        this, databaseToken, nextLeaseToken, ownerId, ownerGeneration, demand, false);
    if (!status.isOk()) return status;
    if (!owners.add(ownerId, ownerGeneration)) {
      lease.complete();
      return StatusCode.INVARIANT_BROKEN;
    }
    waiting[(waitingHead + waitingCount) % waiting.length] = lease;
    waitingCount++;
    nextLeaseToken++;
    return StatusCode.RETRY;
  }

  private StatusCode grow(
      long ownerId, long ownerGeneration, ResourceDemand total,
      ResourceLease lease) {
    if (!lease.matches(this, databaseToken, ownerId, ownerGeneration)) {
      return StatusCode.NOT_OWNER;
    }
    if (!lease.monotonic(total)) return StatusCode.CONFLICT;
    if (lease.matchesTarget(total)) return StatusCode.OK;
    if (!live.growthAvailable(plan, lendableAccountedBytes(), lease, total)) {
      return StatusCode.RETRY;
    }
    live.addGrowth(lease, total);
    return lease.grow(total);
  }

  private int waitingOffset(ResourceLease lease) {
    for (int offset = 0; offset < waitingCount; offset++) {
      if (waiting[(waitingHead + offset) % waiting.length] == lease) return offset;
    }
    return -1;
  }

  private void removeWaiting(int offset) {
    for (int current = offset; current < waitingCount - 1; current++) {
      int target = (waitingHead + current) % waiting.length;
      waiting[target] = waiting[(target + 1) % waiting.length];
    }
    waiting[(waitingHead + waitingCount - 1) % waiting.length] = null;
    waitingCount--;
    if (waitingCount == 0) waitingHead = 0;
  }

  private long lendableAccountedBytes() {
    return plan.accountedCapacityBytes() - retainedDatabaseAccountedBytes;
  }

  private long maximumRetainedAccountedBytes() {
    return plan.accountedCapacityBytes();
  }

  private boolean waitingFits(long lendableBytes) {
    for (int offset = 0; offset < waitingCount; offset++) {
      ResourceLease lease = waiting[(waitingHead + offset) % waiting.length];
      if (lease == null || lease.accountedBytes() > lendableBytes) return false;
    }
    return true;
  }
}
