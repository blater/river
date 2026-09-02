package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Bounded O(1) primitive mutation metadata, allocated lazily in fixed-size chunks. */
final class PendingMutationMetadata {
  private static final int CHUNK_SHIFT = 8;
  private static final int CHUNK_ENTRIES = 1 << CHUNK_SHIFT;
  private static final int CHUNK_MASK = CHUNK_ENTRIES - 1;
  private PendingMutationMetadataChunk[] chunks = new PendingMutationMetadataChunk[0];
  private final int capacity;
  private final int maximumChunks;
  private int allocatedChunks;

  PendingMutationMetadata(int maximumEntries) {
    if (maximumEntries <= 0) {
      throw new IllegalArgumentException("invalid pending mutation capacity");
    }
    capacity = maximumEntries;
    maximumChunks = (int) (((long) maximumEntries + CHUNK_ENTRIES - 1) >>> CHUNK_SHIFT);
  }

  int capacity() { return capacity; }

  StatusCode reserve(int first, int additional) {
    if (first < 0 || additional <= 0 || first > capacity - additional) {
      return additional <= 0 || first < 0
          ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.RESOURCE_EXHAUSTED;
    }
    int lastChunk = (first + additional - 1) >>> CHUNK_SHIFT;
    try {
      if (lastChunk >= chunks.length) growReferences(lastChunk + 1);
      for (int index = first >>> CHUNK_SHIFT; index <= lastChunk; index++) {
        if (chunks[index] == null) {
          chunks[index] = new PendingMutationMetadataChunk(
              Math.min(CHUNK_ENTRIES, capacity - (index << CHUNK_SHIFT)));
          allocatedChunks++;
        }
      }
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  int operationAt(int index) { return chunk(index).operations[offset(index)]; }
  int rowLengthAt(int index) { return chunk(index).rowLengths[offset(index)]; }
  int rowOffsetAt(int index) { return chunk(index).rowOffsets[offset(index)]; }
  long keyAt(int index) { return chunk(index).keys[offset(index)]; }
  long spaceAt(int index) { return chunk(index).spaces[offset(index)]; }
  long previousRowIdAt(int index) { return chunk(index).previousRowIds[offset(index)]; }
  boolean retainedAt(int index) { return chunk(index).retained[offset(index)]; }

  long accountedBytesForCount(int entries) {
    int required = entries <= 0 ? 0
        : (int) (((long) entries + CHUNK_ENTRIES - 1) >>> CHUNK_SHIFT);
    int retained = Math.max(required, allocatedChunks);
    int references = retained == 0 ? chunks.length : referenceCapacity(retained);
    return retained * 12_288L + references * Long.BYTES;
  }

  void release() {
    chunks = new PendingMutationMetadataChunk[0];
    allocatedChunks = 0;
  }

  void set(
      int index, int operation, long space, long key, long previousRowId,
      int rowOffset, int rowLength) {
    PendingMutationMetadataChunk chunk = chunk(index);
    int offset = offset(index);
    chunk.operations[offset] = operation;
    chunk.spaces[offset] = space;
    chunk.keys[offset] = key;
    chunk.previousRowIds[offset] = previousRowId;
    chunk.rowOffsets[offset] = rowOffset;
    chunk.rowLengths[offset] = rowLength;
  }

  void retain(int index, boolean value) { chunk(index).retained[offset(index)] = value; }

  void copy(int source, int target, int rowOffset) {
    set(target, operationAt(source), spaceAt(source), keyAt(source),
        previousRowIdAt(source), rowOffset, rowLengthAt(source));
  }

  void clear(int index) {
    PendingMutationMetadataChunk chunk = chunk(index);
    int offset = offset(index);
    chunk.operations[offset] = chunk.rowLengths[offset] = chunk.rowOffsets[offset] = 0;
    chunk.keys[offset] = chunk.spaces[offset] = chunk.previousRowIds[offset] = 0;
    chunk.retained[offset] = false;
  }

  private PendingMutationMetadataChunk chunk(int index) {
    return chunks[index >>> CHUNK_SHIFT];
  }

  private void growReferences(int required) {
    int next = referenceCapacity(required);
    PendingMutationMetadataChunk[] grown = new PendingMutationMetadataChunk[next];
    System.arraycopy(chunks, 0, grown, 0, chunks.length);
    chunks = grown;
  }

  private int referenceCapacity(int required) {
    int next = chunks.length == 0 ? Math.min(4, maximumChunks) : chunks.length;
    while (next < required) next = next > maximumChunks / 2 ? maximumChunks : next << 1;
    return next;
  }

  private static int offset(int index) { return index & CHUNK_MASK; }
}
