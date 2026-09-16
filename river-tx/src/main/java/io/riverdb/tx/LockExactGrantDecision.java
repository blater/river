package io.riverdb.tx;

/** Sole ordered grant decision and optional aggregate cause publication for one scheduler. */
final class LockExactGrantDecision {
  private final LockExactTable table;

  LockExactGrantDecision(LockExactTable owner) { table = owner; }

  boolean grantable(long resource, long request) {
    return evaluate(resource, request, false);
  }

  /**
   * Replays once under the unchanged lock-manager monitor after the scheduler drain.
   * Admission calls this only for a surviving queued request counted as an actual block.
   */
  void recordActualBlock(long request) {
    if (!table.blockCausality.active()) return;
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    if (evaluate(requests.resources[offset], request, true)) {
      table.blockCausality.unclassifiedBlock();
    }
  }

  /** Description adds identity to the same failing branch and never decides policy separately. */
  private boolean evaluate(long resource, long request, boolean describe) {
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int resourceOffset = LockTypedSlots.offset(resource);
    int offset = LockTypedSlots.offset(request);
    long transaction = requests.transactions[offset];
    boolean conversion = table.state.requests.conversion(request);
    LockQueueKind waiterQueue = conversion ? LockQueueKind.CONVERSION : LockQueueKind.ORDINARY;
    if (table.lifecycle.frozen(transaction)) {
      if (describe) table.blockCausality.unclassifiedBlock();
      return false;
    }
    if (!queueGrantable(resource, request, requests, offset, conversion, waiterQueue, describe)) {
      return false;
    }
    if (!intervalGrantable(resource, request, transaction, resources, resourceOffset, conversion,
        waiterQueue, describe)) {
      return false;
    }
    return activeGrantable(resource, request, transaction, resources, requests,
        resourceOffset, offset, waiterQueue, describe);
  }

  private boolean queueGrantable(
      long resource, long request, LockExactRequestStore.Chunk requests, int requestOffset,
      boolean conversion,
      LockQueueKind waiterQueue, boolean describe) {
    long blocker;
    if (conversion) {
      if (conversionHead(resource) == request) return true;
      blocker = LockTypedSlots.decode(requests.previousConversion[requestOffset]);
      if (blocker < 0) blocker = conversionHead(resource);
      if (describe) recordQueueBlock(request, blocker, waiterQueue,
          LockDeadlockEdgeKind.FIFO_FAIRNESS, LockGrantPrecondition.FIFO_QUEUE_HEAD);
      return false;
    }
    blocker = conversionHead(resource);
    if (blocker >= 0) {
      if (describe) recordQueueBlock(request, blocker, waiterQueue,
          LockDeadlockEdgeKind.CONVERSION_PRIORITY,
          LockGrantPrecondition.CONVERSION_QUEUE_EMPTY);
      return false;
    }
    if (waitHead(resource) == request) return true;
    blocker = LockTypedSlots.decode(requests.previousResource[requestOffset]);
    if (blocker < 0) blocker = waitHead(resource);
    if (describe) recordQueueBlock(request, blocker, waiterQueue,
        LockDeadlockEdgeKind.FIFO_FAIRNESS, LockGrantPrecondition.FIFO_QUEUE_HEAD);
    return false;
  }

  private boolean intervalGrantable(
      long resource, long request, long transaction, LockExactResourceStore.Chunk resources,
      int resourceOffset, boolean conversion, LockQueueKind waiterQueue, boolean describe) {
    if (!interval(resources.scopes[resourceOffset]) || conversion) return true;
    long blocker = table.conflicts.earlierBlocker(resource, request);
    if (blocker >= 0) {
      if (describe) recordQueueBlock(request, blocker, waiterQueue,
          LockDeadlockEdgeKind.FIFO_FAIRNESS,
          LockGrantPrecondition.NO_EARLIER_INCOMPATIBLE_WAITER);
      return false;
    }
    blocker = table.conflicts.conversionBlocker(resource, transaction);
    if (blocker < 0) return true;
    if (describe) recordQueueBlock(request, blocker, waiterQueue,
        LockDeadlockEdgeKind.CONVERSION_PRIORITY,
        LockGrantPrecondition.CONVERSION_QUEUE_EMPTY);
    return false;
  }

  private boolean activeGrantable(
      long resource, long request, long transaction, LockExactResourceStore.Chunk resources,
      LockExactRequestStore.Chunk requests, int resourceOffset, int requestOffset,
      LockQueueKind waiterQueue, boolean describe) {
    long blocker;
    if (interval(resources.scopes[resourceOffset])) {
      blocker = table.conflicts.activeBlocker(resource, transaction, requests.modes[requestOffset]);
      if (blocker < 0) return true;
    } else {
      if (exactHoldingGrantable(resourceOffset, requestOffset, requests, resources)) return true;
      if (!describe) return false;
      blocker = table.conflicts.exactActiveBlocker(
          resource, transaction, requests.modes[requestOffset]);
      if (blocker < 0) {
        table.blockCausality.unclassifiedBlock();
        return false;
      }
    }
    if (describe) recordActiveOwner(request, blocker, waiterQueue);
    return false;
  }

  private boolean exactHoldingGrantable(
      int resourceOffset, int requestOffset, LockExactRequestStore.Chunk requests,
      LockExactResourceStore.Chunk resources) {
    long holding = requests.holdings[requestOffset];
    LockExactHoldingStore.Chunk holdings = table.state.holdings.record(holding);
    int holdingOffset = LockTypedSlots.offset(holding);
    if (holdings.active[holdingOffset] == 0) {
      return LockExactCompatibility.grantable(
          requests.modes[requestOffset], resources.ownerCounts[resourceOffset],
          resources.sharedCounts[resourceOffset], resources.updateCounts[resourceOffset]);
    }
    return holdings.modes[holdingOffset] >= requests.modes[requestOffset]
        || LockExactCompatibility.upgradeable(
            requests.modes[requestOffset], resources.ownerCounts[resourceOffset],
            resources.sharedCounts[resourceOffset], resources.updateCounts[resourceOffset]);
  }

  private void recordActiveOwner(
      long request, long blocker, LockQueueKind waiterQueue) {
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    long resource = requests.resources[offset];
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    LockExactHoldingStore.Chunk holdings = table.state.holdings.record(blocker);
    table.blockCausality.block(
        LockExactTable.LOCK_SCOPES[resources.scopes[LockTypedSlots.offset(resource)]],
        LockExactTable.LOCK_MODES[requests.modes[offset]],
        LockExactTable.LOCK_MODES[holdings.modes[LockTypedSlots.offset(blocker)]],
        waiterQueue,
        LockDeadlockEdgeKind.ACTIVE_OWNER,
        LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER);
  }

  private void recordQueueBlock(
      long request,
      long blocker,
      LockQueueKind waiterQueue,
      LockDeadlockEdgeKind relationship,
      LockGrantPrecondition precondition) {
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    LockExactRequestStore.Chunk blockers = table.state.requests.record(blocker);
    int blockerOffset = LockTypedSlots.offset(blocker);
    long resource = requests.resources[offset];
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    table.blockCausality.block(
        LockExactTable.LOCK_SCOPES[resources.scopes[LockTypedSlots.offset(resource)]],
        LockExactTable.LOCK_MODES[requests.modes[offset]],
        LockExactTable.LOCK_MODES[blockers.modes[blockerOffset]],
        waiterQueue,
        relationship,
        precondition);
  }

  private long waitHead(long resource) {
    return LockTypedSlots.decode(table.state.resources.record(resource)
        .waitHeads[LockTypedSlots.offset(resource)]);
  }

  private long conversionHead(long resource) {
    return LockTypedSlots.decode(table.state.resources.record(resource)
        .conversionHeads[LockTypedSlots.offset(resource)]);
  }

  private static boolean interval(byte scope) {
    return LockIntervalIndex.intervalScope(scope);
  }
}
