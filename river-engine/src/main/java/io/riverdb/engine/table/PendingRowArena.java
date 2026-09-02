package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapInsertResult;
import io.riverdb.storage.heap.HeapPage;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/**
 * Lazily allocated bounded heap chunks retaining transaction row bytes without replacement.
 * Each cold admission creates one byte array and one retained {@link ByteBuffer} view; warmed row
 * append, copy, compaction, and reuse allocate nothing.
 */
final class PendingRowArena {
  private static final int ROWS_PER_CHUNK = 8;

  private byte[][] chunks = new byte[0][];
  private ByteBuffer[] views = new ByteBuffer[0];
  private final int chunkBytes;
  private final int maximumChunks;
  private final int maximumRowBytes;
  private final PendingRowChunkAllocator allocator;
  private int allocatedChunks;
  private int currentChunk;
  private int currentOffset;
  private int compactChunk;
  private int compactOffset;

  PendingRowArena(int maximumRows, int maximumRowBytes) {
    this(maximumRows, maximumRowBytes, PendingRowChunkAllocator.HEAP);
  }

  PendingRowArena(
      int maximumRows,
      int maximumRowBytes,
      PendingRowChunkAllocator chunkAllocator) {
    this.maximumRowBytes = maximumRowBytes;
    allocator = chunkAllocator;
    int safeMaximumRowBytes = Math.max(1, maximumRowBytes);
    long preferred = (long) safeMaximumRowBytes * ROWS_PER_CHUNK;
    long maximumBytes = Math.max(1L, (long) maximumRows * safeMaximumRowBytes);
    chunkBytes = (int) Math.min(maximumBytes, preferred);
    maximumChunks = (int) ((maximumBytes + chunkBytes - 1) / chunkBytes);
  }

  StatusCode reserveRows(int[] rowLengths, int start, int count) {
    if (rowLengths == null || start < 0 || count <= 0 || start > rowLengths.length - count) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int simulatedChunk = currentChunk;
    int simulatedOffset = currentOffset;
    for (int index = 0; index < count; index++) {
      int rowBytes = rowLengths[start + index];
      if (rowBytes <= 0 || rowBytes > maximumRowBytes) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      if (simulatedOffset > chunkBytes - rowBytes) {
        simulatedChunk++;
        simulatedOffset = 0;
      }
      if (simulatedChunk >= maximumChunks) return StatusCode.RESOURCE_EXHAUSTED;
      simulatedOffset += rowBytes;
    }
    for (int index = currentChunk; index <= simulatedChunk; index++) {
      if (index >= chunks.length || chunks[index] == null) {
        try {
          allocateChunk(index);
        } catch (OutOfMemoryError failure) {
          return StatusCode.RESOURCE_EXHAUSTED;
        }
      }
    }
    return StatusCode.OK;
  }

  StatusCode reserveRow(int rowBytes) {
    if (rowBytes <= 0 || rowBytes > maximumRowBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int requiredChunk = currentOffset <= chunkBytes - rowBytes
        ? currentChunk : currentChunk + 1;
    if (requiredChunk >= maximumChunks) return StatusCode.RESOURCE_EXHAUSTED;
    if (requiredChunk < chunks.length && chunks[requiredChunk] != null) return StatusCode.OK;
    try {
      allocateChunk(requiredChunk);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StatusCode.OK;
  }

  long accountedBytesForRows(int[] rowLengths, int start, int count) {
    if (rowLengths == null || start < 0 || count <= 0 || start > rowLengths.length - count) {
      return -1;
    }
    int simulatedChunk = currentChunk;
    int simulatedOffset = currentOffset;
    for (int index = 0; index < count; index++) {
      int rowBytes = rowLengths[start + index];
      if (rowBytes <= 0 || rowBytes > maximumRowBytes) return -1;
      if (simulatedOffset > chunkBytes - rowBytes) {
        simulatedChunk++;
        simulatedOffset = 0;
      }
      if (simulatedChunk >= maximumChunks) return -1;
      simulatedOffset += rowBytes;
    }
    return accountedBytesForChunks(Math.max(allocatedChunks, simulatedChunk + 1));
  }

  long accountedBytesForRow(int rowBytes) {
    if (rowBytes <= 0 || rowBytes > maximumRowBytes) return -1;
    int required = currentOffset <= chunkBytes - rowBytes
        ? currentChunk + 1 : currentChunk + 2;
    if (required > maximumChunks) return -1;
    return accountedBytesForChunks(Math.max(allocatedChunks, required));
  }

  long accountedBytes() {
    return accountedBytesForChunks(allocatedChunks);
  }

  void release() {
    chunks = new byte[0][];
    views = new ByteBuffer[0];
    allocatedChunks = currentChunk = currentOffset = compactChunk = compactOffset = 0;
  }

  int append(ByteBuffer source, int sourceStart, int length) {
    advanceFor(length);
    int encodedOffset = encodeOffset(currentChunk, currentOffset);
    byte[] chunk = chunks[currentChunk];
    for (int index = 0; index < length; index++) {
      chunk[currentOffset + index] = source.get(sourceStart + index);
    }
    currentOffset += length;
    return encodedOffset;
  }

  int appendDeletion() {
    advanceFor(1);
    int encodedOffset = encodeOffset(currentChunk, currentOffset);
    chunks[currentChunk][currentOffset++] = 0;
    return encodedOffset;
  }

  void copyTo(int encodedOffset, int length, ByteBuffer target, int targetOffset) {
    int chunk = chunkIndex(encodedOffset);
    int offset = chunkOffset(encodedOffset);
    for (int index = 0; index < length; index++) {
      target.put(targetOffset + index, chunks[chunk][offset + index]);
    }
  }

  StatusCode insertInto(
      int encodedOffset,
      int length,
      ByteBuffer heap,
      HeapInsertResult result) {
    int chunk = chunkIndex(encodedOffset);
    return HeapPage.insertFrom(
        heap, views[chunk], chunkOffset(encodedOffset), length, result);
  }

  void setResult(int encodedOffset, int length, HeapRowResult result) {
    int chunk = chunkIndex(encodedOffset);
    result.set(views[chunk], 0, chunkOffset(encodedOffset), length);
  }

  void beginCompaction() {
    compactChunk = 0;
    compactOffset = 0;
  }

  int compactRow(int sourceEncodedOffset, int length) {
    if (compactOffset > chunkBytes - length) {
      compactChunk++;
      compactOffset = 0;
    }
    int targetEncodedOffset = encodeOffset(compactChunk, compactOffset);
    int sourceChunk = chunkIndex(sourceEncodedOffset);
    int sourceOffset = chunkOffset(sourceEncodedOffset);
    byte[] source = chunks[sourceChunk];
    byte[] target = chunks[compactChunk];
    for (int index = 0; index < length; index++) {
      target[compactOffset + index] = source[sourceOffset + index];
    }
    compactOffset += length;
    return targetEncodedOffset;
  }

  void finishCompaction() {
    currentChunk = compactChunk;
    currentOffset = compactOffset;
  }

  void truncateTo(int encodedOffset) {
    currentChunk = chunkIndex(encodedOffset);
    currentOffset = chunkOffset(encodedOffset);
  }

  int endOffset() {
    return encodeOffset(currentChunk, currentOffset);
  }

  private void advanceFor(int rowBytes) {
    if (currentOffset > chunkBytes - rowBytes) {
      currentChunk++;
      currentOffset = 0;
    }
  }

  private void allocateChunk(int index) {
    if (index >= chunks.length) growReferences(index + 1);
    byte[] allocated = allocator.allocate(chunkBytes);
    ByteBuffer view = ByteBuffer.wrap(allocated);
    chunks[index] = allocated;
    views[index] = view;
    allocatedChunks++;
  }

  private void growReferences(int required) {
    int next = referenceCapacity(required);
    byte[][] grownChunks = new byte[next][];
    ByteBuffer[] grownViews = new ByteBuffer[next];
    System.arraycopy(chunks, 0, grownChunks, 0, chunks.length);
    System.arraycopy(views, 0, grownViews, 0, views.length);
    chunks = grownChunks;
    views = grownViews;
  }

  private long accountedBytesForChunks(int retained) {
    int references = retained == 0 ? chunks.length : referenceCapacity(retained);
    return retained * ((long) chunkBytes + 128) + references * 2L * Long.BYTES;
  }

  private int referenceCapacity(int required) {
    int next = chunks.length == 0 ? Math.min(4, maximumChunks) : chunks.length;
    while (next < required) next = next > maximumChunks / 2 ? maximumChunks : next << 1;
    return next;
  }

  private int encodeOffset(int chunk, int offset) {
    return chunk * chunkBytes + offset;
  }

  private int chunkIndex(int encodedOffset) {
    return encodedOffset / chunkBytes;
  }

  private int chunkOffset(int encodedOffset) {
    return encodedOffset % chunkBytes;
  }
}
