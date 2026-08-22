package io.riverdb.format.catalog;

import io.riverdb.base.error.StatusCode;

/** Reserved negative key range for versioned catalog continuation rows. */
public final class CatalogContinuationKey {
  public static final int SPACE = 1;
  public static final long FIRST = Long.MIN_VALUE;
  public static final long LAST = Long.MIN_VALUE / 2 - 1;
  public static final long ALLOCATION_WATERMARK_KEY = Long.MIN_VALUE / 2;
  public static final long VACUUM_PROGRESS_KEY = ALLOCATION_WATERMARK_KEY + 1;
  public static final long MAXIMUM_ALLOCATION = LAST - FIRST + 1;

  private CatalogContinuationKey() {
  }

  public static long first(long allocation) {
    if (allocation < 0 || allocation > LAST - FIRST) return 0;
    return FIRST + allocation;
  }

  public static boolean validRange(long first, int count) {
    return first >= FIRST
        && first <= LAST
        && count > 0
        && (long) count - 1 <= LAST - first;
  }

  public static long at(long first, int ordinal, int count) {
    return validRange(first, count) && ordinal >= 0 && ordinal < count
        ? first + ordinal : 0;
  }

  public static StatusCode reserve(
      long nextAllocation, int count, CatalogContinuationReservation result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (nextAllocation < 0
        || count <= 0
        || count > CatalogHeaderCodec.MAXIMUM_SEGMENTS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long first = first(nextAllocation);
    if (!validRange(first, count)) return StatusCode.RESOURCE_EXHAUSTED;
    result.set(first, nextAllocation + count);
    return StatusCode.OK;
  }
}
