package io.riverdb.tx;

/** Narrow typed arena for transaction-local exact-lock chain heads. */
final class LockExactTransactionStore extends LockTypedSlots {
  static final class Chunk extends LockTypedSlots.Chunk {
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
    final long[] deadlockScheduled = new long[4];
  }

  // Includes all retained DFS workspace; detection never grows storage after admission.
  LockExactTransactionStore(LockSegmentArena arena) { super(arena, 51_744); }
  Chunk record(long slot) { return (Chunk) chunk(slot); }
  @Override Object newChunk(long index) { return new Chunk(); }
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
