package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockWaitState;

/** Allocation-free derived blocker enumeration for one transaction DFS frame. */
final class LockExactBlockerCursor {
  private static final byte EXACT_OWNERS = 0;
  private static final byte EXACT_CONVERSIONS = 1;
  private static final byte EXACT_FAIRNESS = 2;
  private static final byte INTERVAL_OWNERS = 3;
  private static final byte INTERVAL_CONVERSIONS = 4;
  private static final byte INTERVAL_FAIRNESS = 5;
  private final LockExactTable table;
  private final LockExactConversionBlockers conversions;
  private final LockExactFairnessBlockers fairness;
  private long edgeRequest = -1;
  private long edgeBlockerRecord = -1;
  private long edgeBlockingResource = -1;
  private byte edgeKind;
  private byte edgePrecondition;

  LockExactBlockerCursor(LockExactTable owner) {
    table = owner;
    conversions = new LockExactConversionBlockers(owner);
    fairness = new LockExactFairnessBlockers(owner);
  }

  void begin(long transaction) {
    LockExactTransactionStore.Chunk chunk = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    chunk.frameRequests[offset] = chunk.requestHeads[offset];
    chunk.frameOwners[offset] = 0;
    chunk.frameActiveRequests[offset] = 0;
    chunk.frameIntervals[offset] = -1;
    chunk.frameFairnessCandidates[offset] = 0;
    chunk.frameModes[offset] = 0;
    chunk.framePhases[offset] = EXACT_OWNERS;
  }

  long edgeRequest() { return edgeRequest; }
  long edgeBlockerRecord() { return edgeBlockerRecord; }
  long edgeBlockingResource() { return edgeBlockingResource; }
  byte edgeKind() { return edgeKind; }
  byte edgePrecondition() { return edgePrecondition; }

  long next(long transaction) {
    LockExactTransactionStore.Chunk frame = table.state.transactions.record(transaction);
    int frameOffset = LockTypedSlots.offset(transaction);
    while (true) {
      long blocker = nextActiveBlocker(transaction, frame, frameOffset);
      if (blocker >= 0) return blocker;
      long request = LockTypedSlots.decode(frame.frameRequests[frameOffset]);
      if (request < 0) return -1;
      LockExactRequestStore.Chunk requests = table.state.requests.record(request);
      int offset = LockTypedSlots.offset(request);
      frame.frameRequests[frameOffset] = requests.nextTransaction[offset];
      if (LockExactTable.WAIT_STATES[requests.states[offset]] != LockWaitState.QUEUED) continue;
      boolean conversion = table.state.requests.conversion(request);
      long resource = requests.resources[offset];
      frame.frameActiveRequests[frameOffset] = LockTypedSlots.encode(request);
      boolean conversionPriority = !conversion && conversionQueued(resource);
      if (interval(resource)) {
        frame.frameOwners[frameOffset] = 0;
        frame.frameIntervals[frameOffset] = LockIntervalCursor.INITIAL;
        frame.frameFairnessCandidates[frameOffset] = 0;
        frame.frameModes[frameOffset] = 0;
        frame.framePhases[frameOffset] = conversionPriority
            ? INTERVAL_CONVERSIONS : INTERVAL_OWNERS;
      } else {
        LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
        int resourceOffset = LockTypedSlots.offset(resource);
        frame.frameOwners[frameOffset] = resources.ownerHeads[resourceOffset];
        frame.frameFairnessCandidates[frameOffset] = conversion ? 0
            : resources.conversionHeads[resourceOffset];
        frame.framePhases[frameOffset] = conversionPriority
            ? EXACT_CONVERSIONS : EXACT_OWNERS;
      }
    }
  }

  private long nextActiveBlocker(
      long transaction, LockExactTransactionStore.Chunk frame, int frameOffset) {
    long request = LockTypedSlots.decode(frame.frameActiveRequests[frameOffset]);
    if (request < 0) return -1;
    boolean conversion = table.state.requests.conversion(request);
    if (frame.framePhases[frameOffset] == EXACT_OWNERS) {
      long blocker = nextOwner(
          transaction, request, requestResource(request), frame, frameOffset);
      if (blocker >= 0) return blocker;
      frame.framePhases[frameOffset] = conversion ? EXACT_FAIRNESS : EXACT_CONVERSIONS;
    }
    if (frame.framePhases[frameOffset] == EXACT_CONVERSIONS) {
      long blocker = conversions.nextExact(transaction, frame, frameOffset);
      if (blocker >= 0) return requestBlocker(request, blocker,
          LockDeadlockEdgeKind.CONVERSION_PRIORITY,
          LockGrantPrecondition.CONVERSION_QUEUE_EMPTY);
      frame.framePhases[frameOffset] = EXACT_FAIRNESS;
    }
    if (frame.framePhases[frameOffset] == EXACT_FAIRNESS) {
      long blocker = exactFairness(transaction, request, conversion);
      frame.frameActiveRequests[frameOffset] = 0;
      return blocker;
    }
    if (frame.framePhases[frameOffset] == INTERVAL_OWNERS) {
      long blocker = nextIntervalOwner(transaction, request, frame, frameOffset);
      if (blocker >= 0) return blocker;
      frame.frameIntervals[frameOffset] = LockIntervalCursor.INITIAL;
      frame.framePhases[frameOffset] = conversion
          ? INTERVAL_FAIRNESS : INTERVAL_CONVERSIONS;
    }
    if (frame.framePhases[frameOffset] == INTERVAL_CONVERSIONS) {
      long blocker = conversions.nextInterval(
          transaction, requestResource(request), frame, frameOffset);
      if (blocker >= 0) return requestBlocker(request, blocker,
          LockDeadlockEdgeKind.CONVERSION_PRIORITY,
          LockGrantPrecondition.CONVERSION_QUEUE_EMPTY);
      frame.frameIntervals[frameOffset] = LockIntervalCursor.INITIAL;
      frame.framePhases[frameOffset] = INTERVAL_FAIRNESS;
    }
    if (frame.framePhases[frameOffset] == INTERVAL_FAIRNESS) {
      long blocker = conversion
          ? exactFairness(transaction, request, true)
          : fairness.next(transaction, request, frame, frameOffset);
      if (blocker >= 0 && !conversion) return requestBlocker(request, blocker,
          LockDeadlockEdgeKind.FIFO_FAIRNESS,
          LockGrantPrecondition.NO_EARLIER_INCOMPATIBLE_WAITER);
      if (blocker >= 0) return blocker;
      frame.frameActiveRequests[frameOffset] = 0;
      return -1;
    }
    return -1;
  }

  private long nextIntervalOwner(
      long transaction, long request,
      LockExactTransactionStore.Chunk frame, int frameOffset) {
    long requestedResource = requestResource(request);
    long overlap = frame.frameIntervals[frameOffset];
    while (true) {
      if (overlap != LockIntervalCursor.INITIAL) {
        long blocker = nextOwner(transaction, request, overlap, frame, frameOffset);
        if (blocker >= 0) return blocker;
      }
      overlap = overlap == LockIntervalCursor.INITIAL
          ? table.state.intervals.firstOverlap(requestedResource)
          : table.state.intervals.nextOverlap(requestedResource, overlap);
      frame.frameIntervals[frameOffset] = overlap;
      if (overlap < 0) return -1;
      frame.frameOwners[frameOffset] = table.state.resources.record(overlap)
          .ownerHeads[LockTypedSlots.offset(overlap)];
    }
  }

  private long nextOwner(
      long transaction, long request, long blockingResource,
      LockExactTransactionStore.Chunk frame, int frameOffset) {
    long owner = LockTypedSlots.decode(frame.frameOwners[frameOffset]);
    while (owner >= 0) {
      LockExactHoldingStore.Chunk holdings = table.state.holdings.record(owner);
      int offset = LockTypedSlots.offset(owner);
      frame.frameOwners[frameOffset] = holdings.nextResource[offset];
      long ownerTransaction = holdings.transactions[offset];
      if (ownerTransaction != transaction && request >= 0
          && LockExactCompatibility.conflicts(requestMode(request), holdings.modes[offset])) {
        edgeRequest = request;
        edgeBlockerRecord = owner;
        edgeBlockingResource = blockingResource;
        edgeKind = (byte) LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal();
        edgePrecondition = (byte) LockGrantPrecondition.NO_INCOMPATIBLE_ACTIVE_OWNER.ordinal();
        return ownerTransaction;
      }
      owner = LockTypedSlots.decode(frame.frameOwners[frameOffset]);
    }
    return -1;
  }

  private long requestTransaction(long request) {
    return table.state.requests.record(request).transactions[LockTypedSlots.offset(request)];
  }

  private boolean conversionQueued(long requestedResource) {
    if (!interval(requestedResource)) {
      LockExactResourceStore.Chunk resources = table.state.resources.record(requestedResource);
      return resources.conversionHeads[LockTypedSlots.offset(requestedResource)] != 0;
    }
    for (long overlap = table.state.intervals.firstOverlap(requestedResource);
        overlap >= 0; overlap = table.state.intervals.nextOverlap(requestedResource, overlap)) {
      LockExactResourceStore.Chunk resources = table.state.resources.record(overlap);
      if (resources.conversionHeads[LockTypedSlots.offset(overlap)] != 0) return true;
    }
    return false;
  }

  private long exactFairness(long transaction, long request, boolean conversion) {
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    long predecessor = LockTypedSlots.decode(conversion
        ? requests.previousConversion[offset] : requests.previousResource[offset]);
    if (predecessor < 0 || requestTransaction(predecessor) == transaction) return -1;
    return requestBlocker(request, predecessor, LockDeadlockEdgeKind.FIFO_FAIRNESS,
        LockGrantPrecondition.FIFO_QUEUE_HEAD);
  }

  private long requestBlocker(
      long request, long blockerRequest,
      LockDeadlockEdgeKind kind, LockGrantPrecondition precondition) {
    edgeRequest = request;
    edgeBlockerRecord = blockerRequest;
    edgeBlockingResource = requestResource(blockerRequest);
    edgeKind = (byte) kind.ordinal();
    edgePrecondition = (byte) precondition.ordinal();
    return requestTransaction(blockerRequest);
  }

  private int requestMode(long request) {
    return table.state.requests.record(request).modes[LockTypedSlots.offset(request)];
  }

  private long requestResource(long request) {
    return table.state.requests.record(request).resources[LockTypedSlots.offset(request)];
  }

  private boolean interval(long resource) {
    byte scope = table.state.resources.record(resource).scopes[LockTypedSlots.offset(resource)];
    return LockIntervalIndex.intervalScope(scope);
  }
}
