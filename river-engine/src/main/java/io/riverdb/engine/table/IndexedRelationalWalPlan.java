package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWalRecordBatch;
/** Exact chunk plan for one sealed relational WAL group. */
final class IndexedRelationalWalPlan implements LocalWalRecordBatch {
  private int[] firstItems = new int[0];
  private int[] itemCounts = new int[0];
  private int[] streamBytes = new int[0];
  private long[] priorDigests = new long[0];
  private long[] rollingDigests = new long[0];
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
    return plan(walTransactionId, groupOperationId, source, true);
  }

  StatusCode planPrepared(
      long walTransactionId,
      long groupOperationId,
      IndexedRelationalMutationBuffer source) {
    return plan(walTransactionId, groupOperationId, source, false);
  }

  private StatusCode plan(
      long walTransactionId,
      long groupOperationId,
      IndexedRelationalMutationBuffer source,
      boolean allowGrowth) {
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
    status = firstItems.length >= chunkCount
        ? StatusCode.OK
        : allowGrowth ? ensureChunkCapacity(chunkCount) : StatusCode.RESOURCE_EXHAUSTED;
    return status.isOk() ? prepareChunks() : status;
  }

  private StatusCode prepareChunks() {
    if (mutations == null || totalItems <= 0 || chunkCount <= 0) {
      return StatusCode.CONFLICT;
    }
    batchFirstChunk = nextChunk;
    batchChunkCount = 0;
    while (nextItem < totalItems) {
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
    return batchChunkCount == chunkCount && nextItem == totalItems
        ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
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

  IndexedRelationalMutationBuffer mutations() { return mutations; }
  long transactionId() { return transactionId; }
  long operationId() { return operationId; }
  long wholeDigest() { return wholeDigest; }
  long totalStreamBytes() { return totalStreamBytes; }
  long totalEncodedBytes() {
    return IndexedRelationalWalSizing.encodedBytes(totalStreamBytes, batchChunkCount);
  }
  long totalPayloadBytes() { return totalPayloadBytes; }
  long copiedPayloadBytes() {
    long copied = 0;
    for (int chunk = 0; chunk < batchChunkCount; chunk++) {
      long bytes = IndexedRelationalWalCodec.copiedPayloadBytes(this, chunk);
      if (bytes < 0 || copied > Long.MAX_VALUE - bytes) return Long.MAX_VALUE;
      copied += bytes;
    }
    return copied;
  }
  int totalItems() { return totalItems; }
  int chunkCount() { return chunkCount; }
  int batchChunkCount() { return batchChunkCount; }
  @Override
  public int recordCount() { return batchChunkCount; }
  int chunkOrdinalAt(int chunk) { return batchFirstChunk + chunk; }
  int firstItemAt(int chunk) { return firstItems[chunk]; }
  int itemCountAt(int chunk) { return itemCounts[chunk]; }
  int streamBytesAt(int chunk) { return streamBytes[chunk]; }
  long priorDigestAt(int chunk) { return priorDigests[chunk]; }
  long rollingDigestAt(int chunk) { return rollingDigests[chunk]; }
  int payloadBytesAt(int chunk) {
    return IndexedRelationalWalCodec.HEADER_BYTES + streamBytes[chunk];
  }

  @Override
  public int payloadBytes(int record) { return payloadBytesAt(record); }

  @Override
  public StatusCode encodePayload(int record, java.nio.ByteBuffer target) {
    return IndexedRelationalWalCodec.encode(this, record, target);
  }

  StatusCode reserveChunkCapacity(int required) {
    return ensureChunkCapacity(required);
  }

  long accountedBytes() {
    return accountedBytes(firstItems.length);
  }

  long accountedBytesForChunkCapacity(int required) {
    return required < 0 ? -1 : accountedBytes(Math.max(firstItems.length, required));
  }

  private static long accountedBytes(int capacity) {
    return 5L * 16L + capacity * (3L * Integer.BYTES + 2L * Long.BYTES);
  }

  private StatusCode ensureChunkCapacity(int required) {
    if (required <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (firstItems.length >= required) return StatusCode.OK;
    try {
      int[] nextFirstItems = java.util.Arrays.copyOf(firstItems, required);
      int[] nextItemCounts = java.util.Arrays.copyOf(itemCounts, required);
      int[] nextStreamBytes = java.util.Arrays.copyOf(streamBytes, required);
      long[] nextPriorDigests = java.util.Arrays.copyOf(priorDigests, required);
      long[] nextRollingDigests = java.util.Arrays.copyOf(rollingDigests, required);
      firstItems = nextFirstItems;
      itemCounts = nextItemCounts;
      streamBytes = nextStreamBytes;
      priorDigests = nextPriorDigests;
      rollingDigests = nextRollingDigests;
      return StatusCode.OK;
    } catch (OutOfMemoryError exhausted) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
