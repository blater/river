package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Shared byte admission and checked ordinal geometry for lock-state segments. */
final class LockSegmentArena {
  static final int SEGMENT_SHIFT = 8;
  static final int SEGMENT_ENTRIES = 1 << SEGMENT_SHIFT;
  static final int SEGMENT_MASK = SEGMENT_ENTRIES - 1;
  private final long maximumBytes;
  private long accountedBytes;

  LockSegmentArena(LockMemoryEnvelope envelope) {
    maximumBytes = envelope.maximumBytes();
  }

  StatusCode reserve(long bytes) {
    if (bytes <= 0 || accountedBytes > maximumBytes - bytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    accountedBytes += bytes;
    return StatusCode.OK;
  }

  void release(long bytes) { accountedBytes -= bytes; }
  long accountedBytes() { return accountedBytes; }
  long maximumBytes() { return maximumBytes; }

  static boolean validOrdinal(long ordinal) {
    return ordinal >= 0;
  }
  static int segmentOffset(long ordinal) { return (int) ordinal & SEGMENT_MASK; }
}
