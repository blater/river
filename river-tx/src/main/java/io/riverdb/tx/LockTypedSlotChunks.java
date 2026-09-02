package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Accounted chunk ownership for one typed lock-state store. */
final class LockTypedSlotChunks {
  private static final int CHUNK_SHIFT = 8;
  private static final int CHUNK_MASK = (1 << CHUNK_SHIFT) - 1;

  private final LockRadixDirectory chunks;
  private final LockSegmentArena arena;
  private final long chunkBytes;
  private long allocated;

  LockTypedSlotChunks(LockSegmentArena owner, long bytes) {
    arena = owner;
    chunkBytes = bytes;
    chunks = new LockRadixDirectory(owner);
  }

  StatusCode reserve(long index, LockTypedSlots owner) {
    if (index < allocated) return StatusCode.OK;
    StatusCode status = chunks.reserve(index);
    if (!status.isOk()) return status;
    status = arena.reserve(chunkBytes);
    if (!status.isOk()) {
      chunks.remove(index);
      return status;
    }
    try {
      chunks.set(index, owner.newChunk(index));
      allocated++;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      arena.release(chunkBytes);
      chunks.remove(index);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void rollbackTo(long retained, LockTypedSlots owner) {
    while (allocated > retained) {
      long index = --allocated;
      owner.releaseChunk(chunks.get(index));
      chunks.remove(index);
      arena.release(chunkBytes);
    }
  }

  Object chunk(long slot) { return chunks.get(slot >>> CHUNK_SHIFT); }
  long allocated() { return allocated; }
  static long index(long slot) { return slot >>> CHUNK_SHIFT; }
  static int offset(long slot) { return (int) slot & CHUNK_MASK; }
}
