package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Lazily chunked primitive ownership for pending-mutation index nodes. */
final class PendingMutationLatestNodes {
  private static final int CHUNK_SHIFT = 8;
  private static final int CHUNK_ENTRIES = 1 << CHUNK_SHIFT;
  private static final int CHUNK_MASK = CHUNK_ENTRIES - 1;
  private static final long FULL_CHUNK_BYTES = CHUNK_ENTRIES * 36L + 128;

  private PendingMutationLatestIndexChunk[] chunks =
      new PendingMutationLatestIndexChunk[0];
  private final int maximumEntries;
  private final int maximumChunks;
  private final PendingMutationLatestChunkAllocator allocator;
  private int allocatedChunks;

  PendingMutationLatestNodes(int capacity) {
    this(capacity, PendingMutationLatestChunkAllocator.HEAP);
  }

  PendingMutationLatestNodes(
      int capacity, PendingMutationLatestChunkAllocator chunkAllocator) {
    maximumEntries = capacity;
    maximumChunks = chunkCount(capacity);
    allocator = chunkAllocator;
  }

  StatusCode reserve(int requiredEntries) {
    if (requiredEntries < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (requiredEntries > maximumEntries) return StatusCode.RESOURCE_EXHAUSTED;
    int requiredChunks = chunkCount(requiredEntries);
    try {
      if (requiredChunks > chunks.length) growReferences(requiredChunks);
      while (allocatedChunks < requiredChunks) {
        chunks[allocatedChunks] = allocator.allocate(entriesInChunk(allocatedChunks));
        allocatedChunks++;
      }
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  long accountedBytes() { return accountedBytesForChunks(allocatedChunks); }

  long accountedBytesForEntries(int requiredEntries) {
    if (requiredEntries < 0 || requiredEntries > maximumEntries) return -1;
    return accountedBytesForChunks(Math.max(allocatedChunks, chunkCount(requiredEntries)));
  }

  void release() {
    chunks = new PendingMutationLatestIndexChunk[0];
    allocatedChunks = 0;
  }

  void initialize(int node, long space, long key, int latest, int parent) {
    PendingMutationLatestIndexChunk chunk = chunk(node);
    int offset = offset(node);
    chunk.spaces[offset] = space;
    chunk.keys[offset] = key;
    chunk.latest[offset] = latest;
    chunk.parents[offset] = parent;
    chunk.left[offset] = chunk.right[offset] = 0;
    chunk.heights[offset] = 1;
  }

  long space(int node) { return chunk(node).spaces[offset(node)]; }
  long key(int node) { return chunk(node).keys[offset(node)]; }
  int latest(int node) { return chunk(node).latest[offset(node)]; }
  int left(int node) { return chunk(node).left[offset(node)]; }
  int right(int node) { return chunk(node).right[offset(node)]; }
  int parent(int node) { return chunk(node).parents[offset(node)]; }
  int height(int node) { return chunk(node).heights[offset(node)]; }
  void latest(int node, int value) { chunk(node).latest[offset(node)] = value; }
  void left(int node, int value) { chunk(node).left[offset(node)] = value; }
  void right(int node, int value) { chunk(node).right[offset(node)] = value; }
  void parent(int node, int value) { chunk(node).parents[offset(node)] = value; }
  void height(int node, int value) { chunk(node).heights[offset(node)] = value; }

  private PendingMutationLatestIndexChunk chunk(int node) {
    return chunks[(node - 1) >>> CHUNK_SHIFT];
  }

  private static int offset(int node) { return (node - 1) & CHUNK_MASK; }

  private static int chunkCount(int entries) {
    return (int) (((long) entries + CHUNK_ENTRIES - 1) >>> CHUNK_SHIFT);
  }

  private int entriesInChunk(int index) {
    return (int) Math.min(
        CHUNK_ENTRIES, (long) maximumEntries - ((long) index << CHUNK_SHIFT));
  }

  private long accountedBytesForChunks(int retained) {
    if (retained == 0) return chunks.length * (long) Long.BYTES;
    long bytes = retained * FULL_CHUNK_BYTES;
    if (retained == maximumChunks) {
      bytes -= (CHUNK_ENTRIES - entriesInChunk(retained - 1)) * 36L;
    }
    return bytes + referenceCapacity(retained) * (long) Long.BYTES;
  }

  private void growReferences(int required) {
    PendingMutationLatestIndexChunk[] grown =
        new PendingMutationLatestIndexChunk[referenceCapacity(required)];
    System.arraycopy(chunks, 0, grown, 0, chunks.length);
    chunks = grown;
  }

  private int referenceCapacity(int required) {
    int next = chunks.length == 0 ? Math.min(4, maximumChunks) : chunks.length;
    while (next < required) next = next > maximumChunks / 2 ? maximumChunks : next << 1;
    return next;
  }
}
