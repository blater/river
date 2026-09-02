package io.riverdb.base.collection;

import io.riverdb.base.error.StatusCode;

/**
 * Reusable primitive storage for ordered ranges over one flat integer value array.
 *
 * <p>One range may be open at a time. Its values are not visible until {@link #endRange()}
 * succeeds. {@link #cancelRange()} discards them without clearing retained arrays.</p>
 */
public final class IntRangeList {
  private static final int INITIAL_GROWTH = 8;
  private static final int[] EMPTY_VALUES = new int[0];

  private int[] values = EMPTY_VALUES;
  private int[] starts = EMPTY_VALUES;
  private int[] counts = EMPTY_VALUES;
  private int valueCount;
  private int rangeCount;
  private int activeStart = -1;
  private int activeCount;
  private boolean rangeOpen;

  /** Number of completed ranges. */
  public int rangeCount() {
    return rangeCount;
  }

  /** Number of values in completed ranges. */
  public int valueCount() {
    return visibleValueCount();
  }

  /** Retained flat-value capacity. */
  public int valueCapacity() {
    return values.length;
  }

  /** Retained range-metadata capacity. */
  public int rangeCapacity() {
    return starts.length;
  }

  /** Starts a range, reserving metadata geometrically up to the caller's range bound. */
  public StatusCode beginRange(int maximumRanges) {
    if (maximumRanges < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (rangeOpen) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (rangeCount >= maximumRanges) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ensureRangeCapacity(rangeCount + 1, maximumRanges);
    if (status != StatusCode.OK) {
      return status;
    }
    activeStart = valueCount;
    activeCount = 0;
    rangeOpen = true;
    return StatusCode.OK;
  }

  /** Appends one primitive value to the open range subject to a total-value bound. */
  public StatusCode append(int value, int maximumValues) {
    if (!rangeOpen || maximumValues < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (valueCount >= maximumValues) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int required = valueCount + 1;
    StatusCode status = ensureValueCapacity(required, maximumValues);
    if (status != StatusCode.OK) {
      return status;
    }
    values[valueCount] = value;
    valueCount = required;
    activeCount++;
    return StatusCode.OK;
  }

  /** Publishes the open range and its metadata. Empty ranges are valid. */
  public StatusCode endRange() {
    if (!rangeOpen) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    starts[rangeCount] = activeStart;
    counts[rangeCount] = activeCount;
    rangeCount++;
    activeStart = -1;
    activeCount = 0;
    rangeOpen = false;
    return StatusCode.OK;
  }

  /** Discards the open range while preserving all previously completed ranges. */
  public StatusCode cancelRange() {
    if (!rangeOpen) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    valueCount = activeStart;
    activeStart = -1;
    activeCount = 0;
    rangeOpen = false;
    return StatusCode.OK;
  }

  /** Returns the first flat-value index of a completed range, or {@code -1} when invalid. */
  public int rangeStart(int range) {
    return validRange(range) ? starts[range] : -1;
  }

  /** Returns the value count of a completed range, or {@code -1} when invalid. */
  public int rangeCount(int range) {
    return validRange(range) ? counts[range] : -1;
  }

  /** Whether a flat value index belongs to a completed range. */
  public boolean hasValue(int index) {
    return index >= 0 && index < visibleValueCount();
  }

  /** Returns a completed flat value, or {@code Integer.MIN_VALUE}; use {@link #hasValue}. */
  public int valueAt(int index) {
    if (!hasValue(index)) {
      return Integer.MIN_VALUE;
    }
    return values[index];
  }

  /** Clears logical ranges and values while retaining all high-water arrays. */
  public void reset() {
    valueCount = 0;
    rangeCount = 0;
    activeStart = -1;
    activeCount = 0;
    rangeOpen = false;
  }

  private StatusCode ensureRangeCapacity(int required, int maximumRanges) {
    if (required <= starts.length) {
      return StatusCode.OK;
    }
    int newCapacity = BoundedArrayGrowth.capacity(
        starts.length, required, maximumRanges, INITIAL_GROWTH);

    int[] replacementStarts;
    try {
      replacementStarts = new int[newCapacity];
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int[] replacementCounts;
    try {
      replacementCounts = new int[newCapacity];
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    System.arraycopy(starts, 0, replacementStarts, 0, rangeCount);
    System.arraycopy(counts, 0, replacementCounts, 0, rangeCount);
    starts = replacementStarts;
    counts = replacementCounts;
    return StatusCode.OK;
  }

  private StatusCode ensureValueCapacity(int required, int maximumValues) {
    if (required <= values.length) {
      return StatusCode.OK;
    }
    int newCapacity = BoundedArrayGrowth.capacity(
        values.length, required, maximumValues, INITIAL_GROWTH);
    int[] replacement;
    try {
      replacement = new int[newCapacity];
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    System.arraycopy(values, 0, replacement, 0, valueCount);
    values = replacement;
    return StatusCode.OK;
  }

  private boolean validRange(int range) {
    return range >= 0 && range < rangeCount;
  }

  private int visibleValueCount() {
    return rangeOpen ? activeStart : valueCount;
  }
}
