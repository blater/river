package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;

/** Allocation-free owner and fairness probes across canonical exact and interval resources. */
final class LockExactConflicts {
  private final LockExactTable table;
  private final LockIntervalCursor cursor = new LockIntervalCursor();
  private final LockIntervalCursor fairnessCursor = new LockIntervalCursor();

  LockExactConflicts(LockExactTable owner) { table = owner; }

  boolean activeBlocked(LockRequest request, long id, long generation) {
    table.state.intervals.overlaps(request, cursor);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      if (activeBlocker(resource, id, generation, request.mode().ordinal()) >= 0) return true;
    }
    return false;
  }

  boolean conversionBlocked(LockRequest request, long id, long generation) {
    table.state.intervals.overlaps(request, cursor);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      if (conversionBlocker(resource, id, generation) >= 0) return true;
    }
    return false;
  }

  boolean conversionBlocked(long requestedResource, long transaction) {
    return conversionBlocker(requestedResource, transaction) >= 0;
  }

  long conversionBlocker(long requestedResource, long transaction) {
    table.state.intervals.overlaps(requestedResource, cursor);
    long id = table.lifecycle.transactionId(transaction);
    long generation = table.lifecycle.transactionGeneration(transaction);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      long blocker = conversionBlocker(resource, id, generation);
      if (blocker >= 0) return blocker;
    }
    return -1;
  }

  boolean activeBlocked(long requestedResource, long transaction, int requestedMode) {
    return activeBlocker(requestedResource, transaction, requestedMode) >= 0;
  }

  long activeBlocker(long requestedResource, long transaction, int requestedMode) {
    table.state.intervals.overlaps(requestedResource, cursor);
    long id = table.lifecycle.transactionId(transaction);
    long generation = table.lifecycle.transactionGeneration(transaction);
    for (long resource = cursor.next(); resource >= 0; resource = cursor.next()) {
      long blocker = activeBlocker(resource, id, generation, requestedMode);
      if (blocker >= 0) return blocker;
    }
    return -1;
  }

  long exactActiveBlocker(long resource, long transaction, int requestedMode) {
    return activeBlocker(
        resource,
        table.lifecycle.transactionId(transaction),
        table.lifecycle.transactionGeneration(transaction),
        requestedMode);
  }

  boolean earlierBlocked(long requestedResource, long request) {
    return earlierBlocker(requestedResource, request) >= 0;
  }

  long earlierBlocker(long requestedResource, long request) {
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
          if (candidate != request
              && fairnessPredecessorBlocks(candidate, transaction)) {
            return candidate;
          }
          candidate = LockTypedSlots.decode(candidates.nextMode[candidateOffset]);
        }
      }
    }
    return -1;
  }

  boolean earlierBlocked(LockRequest request, long id, long generation) {
    long requestingTransaction = table.state.directory.transaction(id, generation);
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
          if ((table.lifecycle.transactionId(transaction) != id
              || table.lifecycle.transactionGeneration(transaction) != generation)
              && (requestingTransaction < 0
              || fairnessPredecessorBlocks(candidate, requestingTransaction))) return true;
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
          if (fairnessPredecessorBlocks(candidate, transaction)) return true;
          candidate = LockTypedSlots.decode(candidates.nextMode[candidateOffset]);
        }
      }
    }
    return false;
  }

  /** A waiter cannot enforce FIFO against an owner that it needs to release first. */
  boolean fairnessPredecessorBlocks(long candidate, long requestingTransaction) {
    LockExactRequestStore.Chunk requests = table.state.requests.record(candidate);
    int offset = LockTypedSlots.offset(candidate);
    return requests.transactions[offset] != requestingTransaction
        && !activelyBlockedBy(candidate, requestingTransaction);
  }

  private boolean activelyBlockedBy(long request, long transaction) {
    if (transaction < 0 || !table.state.transactions.occupied(transaction)) return false;
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int requestOffset = LockTypedSlots.offset(request);
    table.state.intervals.overlaps(requests.resources[requestOffset], fairnessCursor);
    for (long resource = fairnessCursor.next(); resource >= 0;
        resource = fairnessCursor.next()) {
      LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
      long holding = LockTypedSlots.decode(
          resources.ownerHeads[LockTypedSlots.offset(resource)]);
      while (holding >= 0) {
        LockExactHoldingStore.Chunk holdings = table.state.holdings.record(holding);
        int holdingOffset = LockTypedSlots.offset(holding);
        if (holdings.active[holdingOffset] != 0
            && holdings.transactions[holdingOffset] == transaction
            && LockExactCompatibility.conflicts(
                requests.modes[requestOffset], holdings.modes[holdingOffset])) return true;
        holding = LockTypedSlots.decode(holdings.nextResource[holdingOffset]);
      }
    }
    return false;
  }

  private long activeBlocker(
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
          && LockExactCompatibility.conflicts(requestedMode, holdings.modes[offset])) return holding;
      holding = LockTypedSlots.decode(holdings.nextResource[offset]);
    }
    return -1;
  }

  private long conversionBlocker(long resource, long id, long generation) {
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    long request = LockTypedSlots.decode(
        resources.conversionHeads[LockTypedSlots.offset(resource)]);
    while (request >= 0) {
      LockExactRequestStore.Chunk requests = table.state.requests.record(request);
      int offset = LockTypedSlots.offset(request);
      long transaction = requests.transactions[offset];
      if (table.lifecycle.transactionId(transaction) != id
          || table.lifecycle.transactionGeneration(transaction) != generation) return request;
      request = LockTypedSlots.decode(requests.nextConversion[offset]);
    }
    return -1;
  }

  private long requestOrder(long request) {
    return table.state.requests.record(request)
        .referenceGenerations[LockTypedSlots.offset(request)];
  }
}
