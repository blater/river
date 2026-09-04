package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabaseRetainedLease;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.runtime.ResourceDemand;
import io.riverdb.engine.runtime.ResourceLease;

/** Session-owned aggregate admission for write entries and retained workspace bytes. */
final class IndexedTransactionResourceAdmission {
  private final DatabaseResourceGovernor governor;
  private final ResourceDemand demand = new ResourceDemand();
  private final ResourceLease lease = new ResourceLease();
  private final DatabaseRetainedLease retainedLease = new DatabaseRetainedLease();
  private long ownerId;
  private long generation;
  private long accountedHighWater;
  private long stagedPageHighWater;
  private long versionOperationHighWater;
  private long walByteHighWater;
  private long retainedWriteBytes;
  private long retainedWalBytes;
  private long writeHighWater;

  IndexedTransactionResourceAdmission(DatabaseResourceGovernor resourceGovernor) {
    governor = resourceGovernor;
  }

  StatusCode begin(long transactionId) {
    if (transactionId <= 0 || ownerId != 0 || lease.active() || lease.waiting()) {
      return StatusCode.CONFLICT;
    }
    if (generation == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    ownerId = transactionId;
    generation++;
    return StatusCode.OK;
  }

  StatusCode ensure(long accountedBytes, long writeEntries, boolean waitingAllowed) {
    return ensure(accountedBytes, writeEntries, 0, 0, 0, waitingAllowed);
  }

  StatusCode ensure(
      long accountedBytes, long writeEntries, long stagedPages,
      long versionOperations, long walBytes,
      boolean waitingAllowed) {
    if (ownerId <= 0 || accountedBytes < 0 || writeEntries < 0
        || stagedPages < 0 || versionOperations < 0 || walBytes < 0
        || accountedBytes == 0 && writeEntries == 0
            && stagedPages == 0 && versionOperations == 0 && walBytes == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (governor == null) return StatusCode.OK;
    long requestedBytes = Math.max(accountedHighWater, accountedBytes);
    long requestedWrites = Math.max(writeHighWater, writeEntries);
    long requestedPages = Math.max(stagedPageHighWater, stagedPages);
    long requestedVersions = Math.max(versionOperationHighWater, versionOperations);
    long requestedWal = Math.max(walByteHighWater, walBytes);
    StatusCode status = demand.set(
        requestedBytes, requestedWrites, requestedPages, requestedVersions, requestedWal);
    if (status.isOk()) {
      status = governor.ensure(ownerId, generation, demand, lease, waitingAllowed);
    }
    if (status.isOk()) {
      accountedHighWater = requestedBytes;
      writeHighWater = requestedWrites;
      stagedPageHighWater = requestedPages;
      versionOperationHighWater = requestedVersions;
      walByteHighWater = requestedWal;
    }
    return status;
  }

  StatusCode ensureCommit(
      long writeEntries, long stagedPages, long versionOperations, long walBytes) {
    return ensure(0, writeEntries, stagedPages, versionOperations, walBytes, false);
  }

  StatusCode ensureWrite(long writeBytes, long writeEntries, boolean waitingAllowed) {
    if (writeBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = writeEntries == 0
        ? StatusCode.OK : ensure(0, writeEntries, waitingAllowed);
    if (status.isOk() && writeBytes > retainedWriteBytes) {
      status = ensureRetainedBytes(writeBytes, retainedWalBytes);
      if (status.isOk()) retainedWriteBytes = writeBytes;
    }
    return status;
  }

  StatusCode ensureWalRetainedBytes(long walBytes) {
    if (walBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (walBytes <= retainedWalBytes) return StatusCode.OK;
    StatusCode status = ensureRetainedBytes(retainedWriteBytes, walBytes);
    if (status.isOk()) retainedWalBytes = walBytes;
    return status;
  }

  private StatusCode ensureRetainedBytes(long writeBytes, long walBytes) {
    if (writeBytes < 0 || walBytes < 0 || writeBytes > Long.MAX_VALUE - walBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long target = writeBytes + walBytes;
    return governor == null || target == 0
        ? StatusCode.OK
        : governor.ensureRetainedDatabaseAccountedBytes(target, retainedLease);
  }

  StatusCode end() {
    if (ownerId == 0) return StatusCode.OK;
    StatusCode status = governor == null || !lease.active() && !lease.waiting()
        ? StatusCode.OK : governor.end(ownerId, generation, lease);
    if (status.isOk()) {
      ownerId = accountedHighWater = writeHighWater = 0;
      stagedPageHighWater = versionOperationHighWater = walByteHighWater = 0;
    }
    return status;
  }

  boolean active() {
    return ownerId != 0;
  }

  StatusCode closeSession() {
    if (ownerId != 0 || lease.active() || lease.waiting()) return StatusCode.CONFLICT;
    if (!retainedLease.active() || governor == null) {
      retainedWriteBytes = retainedWalBytes = 0;
      return StatusCode.OK;
    }
    StatusCode status = governor.releaseRetainedDatabaseAccountedBytes(retainedLease);
    if (status.isOk()) retainedWriteBytes = retainedWalBytes = 0;
    return status;
  }
}
