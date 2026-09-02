package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.runtime.ResourceDemand;
import io.riverdb.engine.runtime.ResourceLease;

/** Session-owned aggregate admission for write entries and retained workspace bytes. */
final class IndexedTransactionResourceAdmission {
  private final DatabaseResourceGovernor governor;
  private final ResourceDemand demand = new ResourceDemand();
  private final ResourceLease lease = new ResourceLease();
  private long ownerId;
  private long generation;
  private long accountedHighWater;
  private long retainedWriteBytes;
  private long retainedSessionBytes;
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
    if (governor == null) return StatusCode.OK;
    if (ownerId <= 0 || accountedBytes < 0 || writeEntries < 0
        || accountedBytes == 0 && writeEntries == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long requestedBytes = Math.max(accountedHighWater, accountedBytes);
    long requestedWrites = Math.max(writeHighWater, writeEntries);
    StatusCode status = demand.set(requestedBytes, requestedWrites, 0, 0);
    if (status.isOk()) {
      status = governor.ensure(ownerId, generation, demand, lease, waitingAllowed);
    }
    if (status.isOk() || status == StatusCode.RETRY) {
      accountedHighWater = requestedBytes;
      writeHighWater = requestedWrites;
    }
    return status;
  }

  StatusCode ensureWrite(long writeBytes, long writeEntries, boolean waitingAllowed) {
    if (writeBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = ensure(0, writeEntries, waitingAllowed);
    if (status.isOk() && writeBytes > retainedWriteBytes) {
      status = retainDatabaseBytes(writeBytes - retainedWriteBytes);
      if (status.isOk()) retainedWriteBytes = writeBytes;
    }
    return status;
  }

  StatusCode retainDatabaseBytes(long additional) {
    if (additional < 0 || retainedSessionBytes > Long.MAX_VALUE - additional) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = governor == null || additional == 0
        ? StatusCode.OK : governor.growRetainedDatabaseAccountedBytes(additional);
    if (status.isOk()) retainedSessionBytes += additional;
    return status;
  }

  StatusCode end() {
    if (ownerId == 0) return StatusCode.OK;
    StatusCode status = governor == null || !lease.active() && !lease.waiting()
        ? StatusCode.OK : governor.end(ownerId, generation, lease);
    if (status.isOk()) {
      ownerId = accountedHighWater = writeHighWater = 0;
    }
    return status;
  }

  boolean active() {
    return ownerId != 0;
  }

  StatusCode closeSession() {
    if (ownerId != 0 || lease.active() || lease.waiting()) return StatusCode.CONFLICT;
    if (retainedSessionBytes == 0 || governor == null) {
      retainedSessionBytes = retainedWriteBytes = 0;
      return StatusCode.OK;
    }
    StatusCode status = governor.releaseRetainedDatabaseAccountedBytes(retainedSessionBytes);
    if (status.isOk()) retainedSessionBytes = retainedWriteBytes = 0;
    return status;
  }
}
