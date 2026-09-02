package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;

/** Allocation-free owner and fairness probes across canonical exact and interval resources. */
final class LockExactConflicts {
  private final LockExactTable table;
  private final LockIntervalCursor cursor = new LockIntervalCursor();

  LockExactConflicts(LockExactTable owner) { table = owner; }

  boolean activeBlocked(LockRequest request, long id, long generation) {
    table.state.intervals.overlaps(request, cursor);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      if (activeBlocked(resource, id, generation, request.mode().ordinal())) return true;
    }
    return false;
  }

  boolean conversionBlocked(LockRequest request, long id, long generation) {
    table.state.intervals.overlaps(request, cursor);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      if (conversionBlocked(resource, id, generation)) return true;
    }
    return false;
  }

  boolean conversionBlocked(long requestedResource, long transaction) {
    table.state.intervals.overlaps(requestedResource, cursor);
    long id = table.lifecycle.transactionId(transaction);
    long generation = table.lifecycle.transactionGeneration(transaction);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      if (conversionBlocked(resource, id, generation)) return true;
    }
    return false;
  }

  boolean activeBlocked(long requestedResource, long transaction, int requestedMode) {
    table.state.intervals.overlaps(requestedResource, cursor);
    long id = table.lifecycle.transactionId(transaction);
    long generation = table.lifecycle.transactionGeneration(transaction);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      if (activeBlocked(resource, id, generation, requestedMode)) return true;
    }
    return false;
  }

  boolean earlierBlocked(long requestedResource, long request) {
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int requestOffset = LockTypedSlots.offset(request);
    long order = requests.referenceGenerations[requestOffset];
    int requestedMode = requests.modes[requestOffset];
    long transaction = requests.transactions[requestOffset];
    table.state.intervals.overlaps(requestedResource, cursor);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
      int resourceOffset = LockTypedSlots.offset(resource);
      for (int mode = 0; mode < LockExactTable.LOCK_MODES.length; mode++) {
        if (!LockExactCompatibility.conflicts(requestedMode, mode)) continue;
        long candidate = LockTypedSlots.decode(resources.modeWaitHeads[
            LockExactResourceStore.modeOffset(mode, resourceOffset)]);
        while (candidate >= 0 && requestOrder(candidate) < order) {
          LockExactRequestStore.Chunk candidates = table.state.requests.record(candidate);
          int candidateOffset = LockTypedSlots.offset(candidate);
          if (candidate != request && candidates.transactions[candidateOffset] != transaction) {
            return true;
          }
          candidate = LockTypedSlots.decode(candidates.nextMode[candidateOffset]);
        }
      }
    }
    return false;
  }

  boolean earlierBlocked(LockRequest request, long id, long generation) {
    table.state.intervals.overlaps(request, cursor);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
      int offset = LockTypedSlots.offset(resource);
      for (int mode = 0; mode < LockExactTable.LOCK_MODES.length; mode++) {
        if (!LockExactCompatibility.conflicts(request.mode().ordinal(), mode)) continue;
        long candidate = LockTypedSlots.decode(resources.modeWaitHeads[
            LockExactResourceStore.modeOffset(mode, offset)]);
        while (candidate >= 0) {
          LockExactRequestStore.Chunk candidates = table.state.requests.record(candidate);
          int candidateOffset = LockTypedSlots.offset(candidate);
          long transaction = candidates.transactions[candidateOffset];
          if (table.lifecycle.transactionId(transaction) != id
              || table.lifecycle.transactionGeneration(transaction) != generation) return true;
          candidate = LockTypedSlots.decode(candidates.nextMode[candidateOffset]);
        }
      }
    }
    return false;
  }

  boolean earlierBlocked(long requestedResource, long transaction, int requestedMode) {
    table.state.intervals.overlaps(requestedResource, cursor);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
      int offset = LockTypedSlots.offset(resource);
      for (int mode = 0; mode < LockExactTable.LOCK_MODES.length; mode++) {
        if (!LockExactCompatibility.conflicts(requestedMode, mode)) continue;
        long candidate = LockTypedSlots.decode(resources.modeWaitHeads[
            LockExactResourceStore.modeOffset(mode, offset)]);
        while (candidate >= 0) {
          LockExactRequestStore.Chunk candidates = table.state.requests.record(candidate);
          int candidateOffset = LockTypedSlots.offset(candidate);
          if (candidates.transactions[candidateOffset] != transaction) return true;
          candidate = LockTypedSlots.decode(candidates.nextMode[candidateOffset]);
        }
      }
    }
    return false;
  }

  private boolean activeBlocked(
      long resource, long id, long generation, int requestedMode) {
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    long holding = LockTypedSlots.decode(
        resources.ownerHeads[LockTypedSlots.offset(resource)]);
    while (holding >= 0) {
      LockExactHoldingStore.Chunk holdings = table.state.holdings.record(holding);
      int offset = LockTypedSlots.offset(holding);
      long transaction = holdings.transactions[offset];
      if ((table.lifecycle.transactionId(transaction) != id
          || table.lifecycle.transactionGeneration(transaction) != generation)
          && LockExactCompatibility.conflicts(requestedMode, holdings.modes[offset])) return true;
      holding = LockTypedSlots.decode(holdings.nextResource[offset]);
    }
    return false;
  }

  private boolean conversionBlocked(long resource, long id, long generation) {
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    long request = LockTypedSlots.decode(
        resources.conversionHeads[LockTypedSlots.offset(resource)]);
    while (request >= 0) {
      LockExactRequestStore.Chunk requests = table.state.requests.record(request);
      int offset = LockTypedSlots.offset(request);
      long transaction = requests.transactions[offset];
      if (table.lifecycle.transactionId(transaction) != id
          || table.lifecycle.transactionGeneration(transaction) != generation) return true;
      request = LockTypedSlots.decode(requests.nextConversion[offset]);
    }
    return false;
  }

  private long requestOrder(long request) {
    return table.state.requests.record(request)
        .referenceGenerations[LockTypedSlots.offset(request)];
  }
}
