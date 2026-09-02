package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Lazy segmented primitive-long values addressed through a 64-bit radix directory. */
final class LockLongStore {
  private static final long SEGMENT_BYTES = 24L + 8L * LockSegmentArena.SEGMENT_ENTRIES;
  static final long FIRST_SEGMENT_GROWTH_BYTES =
      LockRadixDirectory.MAXIMUM_NEW_PATH_BYTES + SEGMENT_BYTES;
  private final LockRadixDirectory segments;
  private final LockSegmentArena arena;

  LockLongStore(LockSegmentArena owner) {
    arena = owner;
    segments = new LockRadixDirectory(owner);
  }

  long get(long ordinal) {
    if (!LockSegmentArena.validOrdinal(ordinal)) return 0;
    long[] segment = (long[]) segments.get(ordinal >>> LockSegmentArena.SEGMENT_SHIFT);
    return segment == null ? 0 : segment[LockSegmentArena.segmentOffset(ordinal)];
  }

  void set(long ordinal, long value) {
    long[] segment = (long[]) segments.get(ordinal >>> LockSegmentArena.SEGMENT_SHIFT);
    segment[LockSegmentArena.segmentOffset(ordinal)] = value;
  }

  StatusCode reserve(long ordinal) {
    if (!LockSegmentArena.validOrdinal(ordinal)) return StatusCode.RESOURCE_EXHAUSTED;
    long segmentOrdinal = ordinal >>> LockSegmentArena.SEGMENT_SHIFT;
    if (segments.get(segmentOrdinal) != null) return StatusCode.OK;
    StatusCode status = segments.reserve(segmentOrdinal);
    if (!status.isOk()) return status;
    status = arena.reserve(SEGMENT_BYTES);
    if (!status.isOk()) {
      segments.remove(segmentOrdinal);
      return status;
    }
    try {
      segments.set(segmentOrdinal, new long[LockSegmentArena.SEGMENT_ENTRIES]);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      arena.release(SEGMENT_BYTES);
      segments.remove(segmentOrdinal);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  boolean allocated(long ordinal) {
    return LockSegmentArena.validOrdinal(ordinal)
        && segments.get(ordinal >>> LockSegmentArena.SEGMENT_SHIFT) != null;
  }

  void rollback(long ordinal) {
    long segmentOrdinal = ordinal >>> LockSegmentArena.SEGMENT_SHIFT;
    if (segments.get(segmentOrdinal) == null) return;
    segments.remove(segmentOrdinal);
    arena.release(SEGMENT_BYTES);
  }
}
