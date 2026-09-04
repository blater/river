package io.riverdb.engine.runtime;

import io.riverdb.format.page.PageCodec;

/** Conservative retained-byte model for every indexed page-cache-owned arena. */
final class DatabasePageCacheRetainedLayout {
  private static final long OBJECT_HEADER_BYTES = 16;
  private static final long REFERENCE_BYTES = 8;
  /** Conservative charge for one frame, its buffer views, and validation-proof ownership. */
  private static final long FRAME_OBJECT_AND_VIEW_BYTES = 512;
  /** Conservative charge for fixed cache owners and codec workspaces. */
  private static final long FIXED_OWNER_BYTES = 4_096;
  private static final int MAXIMUM_MAP_ENTRIES = 1 << 29;

  private DatabasePageCacheRetainedLayout() {}

  static int maximumFrameCandidate(long budget) {
    long perFrame = PageCodec.PAGE_BYTES + FRAME_OBJECT_AND_VIEW_BYTES + REFERENCE_BYTES;
    return (int) Math.min(budget / perFrame, MAXIMUM_MAP_ENTRIES);
  }

  static long retainedBytes(
      int current,
      int staging,
      int activeStaged,
      int currentMap,
      int stagingMap,
      int metadataMap) {
    long total = add(FIXED_OWNER_BYTES, frameArenaBytes(current));
    total = add(total, frameArenaBytes(staging));
    total = add(total, mapBytes(currentMap));
    total = add(total, mapBytes(stagingMap));
    total = add(total, multiply(arrayBytes(current, Integer.BYTES), 4));
    total = add(total, mapBytes(currentMap));
    total = add(total, arrayBytes(activeStaged, Integer.BYTES));
    total = add(total, arrayBytes(metadataMap, Integer.BYTES));
    total = add(total, arrayBytes(metadataMap, Byte.BYTES));
    total = add(total, arrayBytes(metadataMap, Long.BYTES));
    total = add(total, arrayBytes(metadataMap, Long.BYTES));
    total = add(total, arrayBytes(metadataMap, Integer.BYTES));
    return add(total, arrayBytes(metadataMap, Long.BYTES));
  }

  static long stagingFrameBytes(int staging, int stagingMap) {
    return add(frameArenaBytes(staging), mapBytes(stagingMap));
  }

  private static long frameArenaBytes(int frames) {
    long frameBytes = add(PageCodec.PAGE_BYTES, FRAME_OBJECT_AND_VIEW_BYTES);
    return add(arrayBytes(frames, REFERENCE_BYTES), multiply(frameBytes, frames));
  }

  private static long mapBytes(int capacity) {
    return multiply(arrayBytes(capacity, Integer.BYTES), 2);
  }

  private static long arrayBytes(int entries, long elementBytes) {
    if (entries < 0) return -1;
    long payload = multiply(entries, elementBytes);
    return payload < 0 ? -1 : align(add(OBJECT_HEADER_BYTES, payload));
  }

  private static long add(long left, long right) {
    return left < 0 || right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }

  private static long multiply(long value, long count) {
    return value < 0 || count < 0 || value != 0 && count > Long.MAX_VALUE / value
        ? -1 : value * count;
  }

  private static long align(long bytes) {
    return bytes < 0 || bytes > Long.MAX_VALUE - 7 ? -1 : (bytes + 7) & ~7L;
  }
}
