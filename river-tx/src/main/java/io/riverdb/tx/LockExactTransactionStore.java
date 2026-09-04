package io.riverdb.tx;

/** Narrow typed arena for transaction-local exact-lock chain heads. */
final class LockExactTransactionStore extends LockTypedSlots {
  static final class Chunk {
    final long[] generations = new long[256];
    final long[] free = new long[256];
    final long[] transactionIds = new long[256];
    final long[] transactionGenerations = new long[256];
    final long[] holdingHeads = new long[256];
    final long[] requestHeads = new long[256];
    final long[] startOrders = new long[256];
    final long[] diagnosticTags = new long[256];
    final long[] diagnosticStepTags = new long[256];
    final long[] metricsEpochs = new long[256];
    final long[] visitEpochs = new long[256];
    final long[] finishEpochs = new long[256];
    final long[] parents = new long[256];
    final long[] parentRequests = new long[256];
    final long[] parentBlockerRecords = new long[256];
    final long[] parentBlockingResources = new long[256];
    final long[] selectedSignatureIndexes = new long[256];
    final long[] selectedEventIndexes = new long[256];
    final long[] frameRequests = new long[256];
    final long[] frameOwners = new long[256];
    final long[] frameActiveRequests = new long[256];
    final long[] frameIntervals = new long[256];
    final long[] frameFairnessCandidates = new long[256];
    final long[] deadlockWorkNext = new long[256];
    final byte[] lifecycleStates = new byte[256];
    final byte[] transactionActive = new byte[256];
    final byte[] parentEdgeKinds = new byte[256];
    final byte[] parentPreconditions = new byte[256];
    final byte[] frameModes = new byte[256];
    final byte[] framePhases = new byte[256];
    final long[] occupied = new long[4];
    final long[] deadlockScheduled = new long[4];
    int used;
  }

  // Includes all retained DFS workspace; detection never grows storage after admission.
  LockExactTransactionStore(LockSegmentArena arena) { super(arena, 51_744); }
  Chunk record(long slot) { return (Chunk) chunk(slot); }
  @Override Object newChunk(long index) { return new Chunk(); }
  @Override long generation(long slot) { return record(slot).generations[offset(slot)]; }
  @Override void generation(long slot, long value) { record(slot).generations[offset(slot)] = value; }
  @Override long freeLink(long slot) { return record(slot).free[offset(slot)]; }
  @Override void freeLink(long slot, long value) { record(slot).free[offset(slot)] = value; }
  @Override void used(long slot, int delta) { record(slot).used += delta; }
  @Override void occupied(long slot, boolean value) {
    Chunk chunk = record(slot);
    int offset = offset(slot);
    if (value) chunk.occupied[offset >>> 6] |= 1L << offset;
    else chunk.occupied[offset >>> 6] &= ~(1L << offset);
  }
  @Override boolean occupied(long slot) {
    int offset = offset(slot);
    return (record(slot).occupied[offset >>> 6] & (1L << offset)) != 0;
  }
  @Override void clear(long slot) {
    Chunk chunk = record(slot);
    int offset = offset(slot);
    chunk.transactionIds[offset] = chunk.transactionGenerations[offset] = 0;
    chunk.holdingHeads[offset] = chunk.requestHeads[offset] = chunk.free[offset] = 0;
    chunk.startOrders[offset] = chunk.diagnosticTags[offset] = 0;
    chunk.diagnosticStepTags[offset] = chunk.metricsEpochs[offset] = 0;
    chunk.visitEpochs[offset] = chunk.finishEpochs[offset] = 0;
    chunk.parents[offset] = chunk.parentRequests[offset] = 0;
    chunk.parentBlockerRecords[offset] = chunk.parentBlockingResources[offset] = 0;
    chunk.selectedSignatureIndexes[offset] = chunk.selectedEventIndexes[offset] = 0;
    chunk.frameRequests[offset] = chunk.frameOwners[offset] = 0;
    chunk.frameActiveRequests[offset] = chunk.frameIntervals[offset] = 0;
    chunk.frameFairnessCandidates[offset] = 0;
    chunk.deadlockWorkNext[offset] = 0;
    chunk.deadlockScheduled[offset >>> 6] &= ~(1L << offset);
    chunk.lifecycleStates[offset] = chunk.transactionActive[offset] = 0;
    chunk.parentEdgeKinds[offset] = chunk.parentPreconditions[offset] = 0;
    chunk.frameModes[offset] = chunk.framePhases[offset] = 0;
  }
}
