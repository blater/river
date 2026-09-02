package io.riverdb.tx;

/** Iterates ordinary interval FIFO blocker edges without allocating queue views. */
final class LockExactFairnessBlockers {
  private final LockExactTable table;

  LockExactFairnessBlockers(LockExactTable owner) { table = owner; }

  long next(
      long transaction, long request,
      LockExactTransactionStore.Chunk frame, int frameOffset) {
    long requestedResource = requestResource(request);
    long overlap = frame.frameIntervals[frameOffset];
    if (overlap == LockIntervalCursor.INITIAL) {
      overlap = table.state.intervals.firstOverlap(requestedResource);
      frame.frameIntervals[frameOffset] = overlap;
    }
    while (overlap >= 0) {
      long blocker = nextOverlap(transaction, request, overlap, frame, frameOffset);
        if (blocker >= 0) return blocker;
      overlap = table.state.intervals.nextOverlap(requestedResource, overlap);
      frame.frameIntervals[frameOffset] = overlap;
      frame.frameModes[frameOffset] = 0;
      frame.frameFairnessCandidates[frameOffset] = 0;
    }
    return -1;
  }

  private long nextOverlap(
      long transaction, long request, long overlap,
      LockExactTransactionStore.Chunk frame, int frameOffset) {
    LockExactResourceStore.Chunk resources = table.state.resources.record(overlap);
    int resourceOffset = LockTypedSlots.offset(overlap);
    int mode = Byte.toUnsignedInt(frame.frameModes[frameOffset]);
    while (mode < LockExactTable.LOCK_MODES.length) {
      if (!LockExactCompatibility.conflicts(requestMode(request), mode)) {
        frame.frameModes[frameOffset] = (byte) ++mode;
        continue;
      }
      long blocker = nextMode(
          transaction, request, resources, resourceOffset, mode, frame, frameOffset);
      if (blocker >= 0) return blocker;
      frame.frameFairnessCandidates[frameOffset] = 0;
      frame.frameModes[frameOffset] = (byte) ++mode;
    }
    return -1;
  }

  private long nextMode(
      long transaction, long request,
      LockExactResourceStore.Chunk resources, int resourceOffset, int mode,
      LockExactTransactionStore.Chunk frame, int frameOffset) {
    long candidate = LockTypedSlots.decode(frame.frameFairnessCandidates[frameOffset]);
    if (candidate < 0) candidate = LockTypedSlots.decode(resources.modeWaitHeads[
        LockExactResourceStore.modeOffset(mode, resourceOffset)]);
    while (candidate >= 0 && requestOrder(candidate) < requestOrder(request)) {
      LockExactRequestStore.Chunk candidates = table.state.requests.record(candidate);
      int offset = LockTypedSlots.offset(candidate);
      long next = LockTypedSlots.decode(candidates.nextMode[offset]);
      if (candidate != request && candidates.transactions[offset] != transaction) {
        if (next >= 0 && requestOrder(next) < requestOrder(request)) {
          frame.frameFairnessCandidates[frameOffset] = LockTypedSlots.encode(next);
        } else {
          frame.frameFairnessCandidates[frameOffset] = 0;
          frame.frameModes[frameOffset] = (byte) (mode + 1);
        }
        return candidate;
      }
      candidate = next;
    }
    return -1;
  }

  private int requestMode(long request) {
    return table.state.requests.record(request).modes[LockTypedSlots.offset(request)];
  }

  private long requestResource(long request) {
    return table.state.requests.record(request).resources[LockTypedSlots.offset(request)];
  }

  private long requestOrder(long request) {
    return table.state.requests.record(request)
        .referenceGenerations[LockTypedSlots.offset(request)];
  }
}
