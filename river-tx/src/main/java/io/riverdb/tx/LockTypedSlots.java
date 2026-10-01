package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Shared reversible allocator for narrow typed lock-state chunks. */
abstract class LockTypedSlots {
  static class Chunk {
    final long[] generations = new long[256];
    final long[] free = new long[256];
    final long[] occupied = new long[4];
    int used;
  }

  private final LockTypedSlotChunks chunks;
  private final LockTypedSlotLifecycle lifecycle = new LockTypedSlotLifecycle();

  LockTypedSlots(LockSegmentArena owner, long bytes) {
    chunks = new LockTypedSlotChunks(owner, bytes);
  }

  final StatusCode reserve(LockSlotReservation reservation) {
    return LockTypedSlotAdmission.reserve(this, lifecycle, chunks, reservation);
  }

  final void rollback(LockSlotReservation reservation) {
    lifecycle.rollback(this, chunks, reservation);
  }

  final void commit(LockSlotReservation reservation) {
    lifecycle.commit(this, reservation);
  }

  final void free(long slot) {
    lifecycle.free(this, slot);
  }

  final Object chunk(long slot) { return chunks.chunk(slot); }
  static int offset(long slot) { return LockTypedSlotChunks.offset(slot); }

  abstract Object newChunk(long index);
  final long generation(long slot) { return metadata(slot).generations[offset(slot)]; }
  final void generation(long slot, long value) { metadata(slot).generations[offset(slot)] = value; }
  final long freeLink(long slot) { return metadata(slot).free[offset(slot)]; }
  final void freeLink(long slot, long value) { metadata(slot).free[offset(slot)] = value; }
  abstract void clear(long slot);
  final void used(long slot, int delta) { metadata(slot).used += delta; }
  final void occupied(long slot, boolean value) {
    Chunk chunk = metadata(slot);
    int offset = offset(slot);
    if (value) chunk.occupied[offset >>> 6] |= 1L << offset;
    else chunk.occupied[offset >>> 6] &= ~(1L << offset);
  }
  final boolean occupied(long slot) {
    int offset = offset(slot);
    return (metadata(slot).occupied[offset >>> 6] & (1L << offset)) != 0;
  }
  private Chunk metadata(long slot) { return (Chunk) chunk(slot); }
  void releaseChunk(Object chunk) {
  }
  static long encode(long slot) { return slot < 0 ? 0 : slot ^ Long.MIN_VALUE; }
  static long decode(long link) { return link == 0 ? -1 : link ^ Long.MIN_VALUE; }
}
