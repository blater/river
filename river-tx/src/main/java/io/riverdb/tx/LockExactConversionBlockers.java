package io.riverdb.tx;

/** Iterates conversion-priority blocker edges without allocating queue views. */
final class LockExactConversionBlockers {
  private final LockExactTable table;

  LockExactConversionBlockers(LockExactTable owner) { table = owner; }

  long nextExact(
      long transaction, LockExactTransactionStore.Chunk frame, int frameOffset) {
    long candidate = LockTypedSlots.decode(frame.frameFairnessCandidates[frameOffset]);
    while (candidate >= 0) {
      LockExactRequestStore.Chunk requests = table.state.requests.record(candidate);
      int offset = LockTypedSlots.offset(candidate);
      frame.frameFairnessCandidates[frameOffset] = requests.nextConversion[offset];
      if (requests.transactions[offset] != transaction) return requests.transactions[offset];
      candidate = LockTypedSlots.decode(frame.frameFairnessCandidates[frameOffset]);
    }
    return -1;
  }

  long nextInterval(
      long transaction, long requestedResource,
      LockExactTransactionStore.Chunk frame, int frameOffset) {
    long overlap = frame.frameIntervals[frameOffset];
    if (overlap == LockIntervalCursor.INITIAL) {
      overlap = table.state.intervals.firstOverlap(requestedResource);
    }
    while (overlap >= 0) {
      long candidate = LockTypedSlots.decode(frame.frameFairnessCandidates[frameOffset]);
      if (candidate < 0) candidate = conversionHead(overlap);
      while (candidate >= 0) {
        LockExactRequestStore.Chunk requests = table.state.requests.record(candidate);
        int offset = LockTypedSlots.offset(candidate);
        frame.frameFairnessCandidates[frameOffset] = requests.nextConversion[offset];
        if (requests.transactions[offset] != transaction) {
          frame.frameIntervals[frameOffset] = requests.nextConversion[offset] == 0
              ? table.state.intervals.nextOverlap(requestedResource, overlap) : overlap;
          return requests.transactions[offset];
        }
        candidate = LockTypedSlots.decode(frame.frameFairnessCandidates[frameOffset]);
      }
      frame.frameFairnessCandidates[frameOffset] = 0;
      overlap = table.state.intervals.nextOverlap(requestedResource, overlap);
      frame.frameIntervals[frameOffset] = overlap;
    }
    return -1;
  }

  private long conversionHead(long resource) {
    return LockTypedSlots.decode(table.state.resources.record(resource)
        .conversionHeads[LockTypedSlots.offset(resource)]);
  }
}
