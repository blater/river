package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Shared reversible allocator for narrow typed lock-state chunks. */
abstract class LockTypedSlots {
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
  abstract long generation(long slot);
  abstract void generation(long slot, long value);
  abstract long freeLink(long slot);
  abstract void freeLink(long slot, long value);
  abstract void clear(long slot);
  abstract void used(long slot, int delta);
  abstract void occupied(long slot, boolean value);
  abstract boolean occupied(long slot);
  void releaseChunk(Object chunk) {
  }
  static long encode(long slot) { return slot < 0 ? 0 : slot ^ Long.MIN_VALUE; }
  static long decode(long link) { return link == 0 ? -1 : link ^ Long.MIN_VALUE; }
}
