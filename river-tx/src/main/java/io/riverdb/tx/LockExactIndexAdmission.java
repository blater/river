package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockRequest;

/** Reversible reservations for exact resource, transaction, holding, and lane directories. */
final class LockExactIndexAdmission {
  private final LockExactTable table;

  LockExactIndexAdmission(LockExactTable owner) { table = owner; }

  StatusCode reserve(
      boolean newResource, boolean newTransaction, boolean newHolding,
      long resource, long transaction, long holding, long requestSlot,
      long id, long generation, long laneId, long laneGeneration, LockRequest request) {
    StatusCode status;
    if (newResource) {
      status = table.state.directory.resourceIndex.reserve(
          resource, LockExactDirectory.resourceHash(request));
      if (!status.isOk()) return status;
      table.admission.indexGrowth |= 1;
      if (LockIntervalIndex.valid(request)) {
        status = table.state.intervals.reserve(resource, request);
        if (!status.isOk()) return status;
        table.admission.indexGrowth |= 16;
      }
    }
    if (newTransaction) {
      status = table.state.directory.transactionIndex.reserve(
          transaction, LockExactDirectory.transactionHash(id, generation));
      if (!status.isOk()) return status;
      table.admission.indexGrowth |= 2;
    }
    if (newHolding) {
      status = table.state.directory.holdingIndex.reserve(
          holding, LockExactDirectory.holdingHash(resource, id, generation));
      if (!status.isOk()) return status;
      table.admission.indexGrowth |= 4;
    }
    if (requestSlot >= 0) {
      status = table.state.directory.laneIndex.reserve(
          requestSlot, LockExactDirectory.laneHash(id, generation, laneId, laneGeneration));
      if (!status.isOk()) return status;
      table.admission.indexGrowth |= 8;
    }
    return StatusCode.OK;
  }

  void commit(
      boolean newResource, boolean newTransaction, boolean newHolding,
      long resource, long transaction, long holding, long requestSlot,
      long id, long generation, long laneId, long laneGeneration, LockRequest request) {
    if (newResource) table.state.directory.resourceIndex.add(
        resource, LockExactDirectory.resourceHash(request));
    if (newResource && LockIntervalIndex.valid(request)) table.state.intervals.add(resource);
    if (newTransaction) table.state.directory.transactionIndex.add(
        transaction, LockExactDirectory.transactionHash(id, generation));
    if (newHolding) table.state.directory.holdingIndex.add(
        holding, LockExactDirectory.holdingHash(resource, id, generation));
    if (requestSlot >= 0) table.state.directory.laneIndex.add(
        requestSlot, LockExactDirectory.laneHash(id, generation, laneId, laneGeneration));
    table.admission.indexGrowth = 0;
  }
}
