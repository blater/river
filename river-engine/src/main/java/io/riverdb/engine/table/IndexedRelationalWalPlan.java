package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWal;

/** Sequential bounded-window plan for one arbitrarily long sealed relational WAL group. */
final class IndexedRelationalWalPlan {
  private final int[] firstItems = new int[LocalWal.MAX_PENDING_RECORDS];
  private final int[] itemCounts = new int[LocalWal.MAX_PENDING_RECORDS];
  private final int[] streamBytes = new int[LocalWal.MAX_PENDING_RECORDS];
  private final long[] priorDigests = new long[LocalWal.MAX_PENDING_RECORDS];
  private final long[] rollingDigests = new long[LocalWal.MAX_PENDING_RECORDS];
  private final IndexedRelationalWalSizing sizing = new IndexedRelationalWalSizing();
  private IndexedRelationalMutationBuffer mutations;
  private long transactionId;
  private long operationId;
  private long wholeDigest;
  private long totalStreamBytes;
  private long totalPayloadBytes;
  private long nextPriorDigest;
  private int mutationGeneration;
  private int totalItems;
  private int chunkCount;
  private int batchFirstChunk;
  private int batchChunkCount;
  private int nextItem;
  private int nextChunk;

  StatusCode plan(
      long walTransactionId,
      long groupOperationId,
      IndexedRelationalMutationBuffer source) {
    reset();
    if (walTransactionId <= 0 || groupOperationId <= 0 || source == null || !source.sealed()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = sizing.measure(source);
    if (!status.isOk()) return status;
    mutations = source;
    mutationGeneration = source.generation();
    transactionId = walTransactionId;
    operationId = groupOperationId;
    wholeDigest = sizing.digest();
    totalStreamBytes = sizing.streamBytes();
    totalPayloadBytes = sizing.payloadBytes();
    totalItems = sizing.items();
    chunkCount = sizing.chunks();
    nextPriorDigest = IndexedRelationalWalCodec.INITIAL_DIGEST;
    return prepareNextBatch();
  }

  StatusCode prepareNextBatch() {
    if (mutations == null || nextItem >= totalItems || nextChunk >= chunkCount) {
      return StatusCode.CONFLICT;
    }
    batchFirstChunk = nextChunk;
    batchChunkCount = 0;
    while (nextItem < totalItems && batchChunkCount < LocalWal.MAX_PENDING_RECORDS) {
      int first = nextItem;
      int packedBytes = 0;
      long prior = nextPriorDigest;
      while (nextItem < totalItems) {
        int itemBytes = IndexedRelationalWalCodec.itemBytes(mutations, nextItem);
        if (packedBytes > IndexedRelationalWalSizing.maximumChunkStreamBytes() - itemBytes) break;
        packedBytes += itemBytes;
        nextPriorDigest = IndexedRelationalWalCodec.digestItem(
            mutations, nextItem, nextPriorDigest);
        nextItem++;
      }
      if (nextItem == first) return StatusCode.INVARIANT_BROKEN;
      firstItems[batchChunkCount] = first;
      itemCounts[batchChunkCount] = nextItem - first;
      streamBytes[batchChunkCount] = packedBytes;
      priorDigests[batchChunkCount] = prior;
      rollingDigests[batchChunkCount] = nextPriorDigest;
      batchChunkCount++;
      nextChunk++;
    }
    return batchChunkCount > 0 ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  void reset() {
    mutations = null;
    transactionId = operationId = wholeDigest = totalStreamBytes = totalPayloadBytes = 0;
    nextPriorDigest = 0;
    mutationGeneration = totalItems = chunkCount = 0;
    batchFirstChunk = batchChunkCount = nextItem = nextChunk = 0;
    sizing.reset();
  }

  boolean valid() {
    return mutations != null && mutations.sealed()
        && mutations.generation() == mutationGeneration && batchChunkCount > 0;
  }

  boolean hasMoreBatches() { return nextItem < totalItems; }
  IndexedRelationalMutationBuffer mutations() { return mutations; }
  long transactionId() { return transactionId; }
  long operationId() { return operationId; }
  long wholeDigest() { return wholeDigest; }
  long totalStreamBytes() { return totalStreamBytes; }
  long totalPayloadBytes() { return totalPayloadBytes; }
  int totalItems() { return totalItems; }
  int chunkCount() { return chunkCount; }
  int batchChunkCount() { return batchChunkCount; }
  int chunkOrdinalAt(int chunk) { return batchFirstChunk + chunk; }
  int firstItemAt(int chunk) { return firstItems[chunk]; }
  int itemCountAt(int chunk) { return itemCounts[chunk]; }
  int streamBytesAt(int chunk) { return streamBytes[chunk]; }
  long priorDigestAt(int chunk) { return priorDigests[chunk]; }
  long rollingDigestAt(int chunk) { return rollingDigests[chunk]; }
  int payloadBytesAt(int chunk) {
    return IndexedRelationalWalCodec.HEADER_BYTES + streamBytes[chunk];
  }
}
