package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockWaitState;

/** Re-evaluates captured blocker edges against the scheduler's canonical state. */
final class LockExactCycleValidator {
  private final LockExactTable table;

  LockExactCycleValidator(LockExactTable owner) { table = owner; }

  boolean validEdge(
      long waiter,
      long request,
      long blocker,
      long blockingResource,
      byte kind,
      byte precondition) {
    if (!occupiedTransaction(waiter) || !queued(request)
        || requestTransaction(request) != waiter) return false;
    if (kind == LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()) {
      return precondition == LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER.ordinal()
          && activeOwner(request, blocker, blockingResource);
    }
    if (kind == LockDeadlockEdgeKind.CONVERSION_PRIORITY.ordinal()) {
      return precondition == LockGrantPrecondition.CONVERSION_QUEUE_EMPTY.ordinal()
          && conversionPriority(request, blocker, blockingResource);
    }
    if (kind != LockDeadlockEdgeKind.FIFO_FAIRNESS.ordinal()) return false;
    if (precondition == LockGrantPrecondition.FIFO_QUEUE_HEAD.ordinal()) {
      return fifoHead(request, blocker, blockingResource);
    }
    return precondition == LockGrantPrecondition.NO_EARLIER_INCOMPATIBLE_WAITER.ordinal()
        && earlierIncompatible(request, blocker, blockingResource);
  }

  long blockerTransaction(long blocker, byte kind) {
    if (kind == LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()) {
      if (!table.state.holdings.occupied(blocker)) return -1;
      return table.state.holdings.record(blocker).transactions[LockTypedSlots.offset(blocker)];
    }
    return queued(blocker) ? requestTransaction(blocker) : -1;
  }

  private boolean activeOwner(long request, long holding, long blockingResource) {
    if (!table.state.holdings.occupied(holding)
        || !table.state.resources.occupied(blockingResource)) return false;
    LockExactHoldingStore.Chunk holdings = table.state.holdings.record(holding);
    int offset = LockTypedSlots.offset(holding);
    long blockerTransaction = holdings.transactions[offset];
    return holdings.active[offset] != 0
        && blockerTransaction != requestTransaction(request)
        && holdings.resources[offset] == blockingResource
        && resourcesOverlap(requestResource(request), blockingResource)
        && LockExactCompatibility.conflicts(requestMode(request), holdings.modes[offset]);
  }

  private boolean conversionPriority(
      long request, long blockerRequest, long blockingResource) {
    if (table.state.requests.conversion(request) || !queued(blockerRequest)) return false;
    LockExactRequestStore.Chunk blockers = table.state.requests.record(blockerRequest);
    int offset = LockTypedSlots.offset(blockerRequest);
    return table.state.requests.conversion(blockerRequest)
        && blockers.transactions[offset] != requestTransaction(request)
        && blockers.resources[offset] == blockingResource
        && resourcesOverlap(requestResource(request), blockingResource);
  }

  private boolean fifoHead(long request, long blockerRequest, long blockingResource) {
    if (!queued(blockerRequest)) return false;
    boolean conversion = table.state.requests.conversion(request);
    if (table.state.requests.conversion(blockerRequest) != conversion) return false;
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    long predecessor = LockTypedSlots.decode(conversion
        ? requests.previousConversion[offset] : requests.previousResource[offset]);
    return predecessor == blockerRequest
        && requestTransaction(blockerRequest) != requestTransaction(request)
        && requestResource(blockerRequest) == blockingResource
        && requestResource(request) == blockingResource;
  }

  private boolean earlierIncompatible(
      long request, long blockerRequest, long blockingResource) {
    if (table.state.requests.conversion(request)
        || !queued(blockerRequest)
        || table.state.requests.conversion(blockerRequest)) return false;
    return requestTransaction(blockerRequest) != requestTransaction(request)
        && requestResource(blockerRequest) == blockingResource
        && resourcesOverlap(requestResource(request), blockingResource)
        && requestOrder(blockerRequest) < requestOrder(request)
        && LockExactCompatibility.conflicts(
            requestMode(request), requestMode(blockerRequest))
        && table.conflicts.fairnessPredecessorBlocks(
            blockerRequest, requestTransaction(request));
  }

  private boolean resourcesOverlap(long requested, long blocking) {
    if (!table.state.resources.occupied(requested)
        || !table.state.resources.occupied(blocking)) return false;
    if (requested == blocking) return true;
    for (long overlap = table.state.intervals.firstOverlap(requested);
        overlap >= 0; overlap = table.state.intervals.nextOverlap(requested, overlap)) {
      if (overlap == blocking) return true;
    }
    return false;
  }

  private boolean queued(long request) {
    if (!table.state.requests.occupied(request)) return false;
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    return LockExactTable.WAIT_STATES[requests.states[LockTypedSlots.offset(request)]]
        == LockWaitState.QUEUED;
  }

  private boolean occupiedTransaction(long transaction) {
    return transaction >= 0 && table.state.transactions.occupied(transaction);
  }

  private long requestTransaction(long request) {
    return table.state.requests.record(request).transactions[LockTypedSlots.offset(request)];
  }

  private long requestResource(long request) {
    return table.state.requests.record(request).resources[LockTypedSlots.offset(request)];
  }

  private int requestMode(long request) {
    return table.state.requests.record(request).modes[LockTypedSlots.offset(request)];
  }

  private long requestOrder(long request) {
    return table.state.requests.record(request)
        .referenceGenerations[LockTypedSlots.offset(request)];
  }
}
