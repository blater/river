package io.riverdb.base.type;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;

/** Primitive backing arrays owned by one reusable {@link SqlValueBuffer}. */
final class SqlValueLaneStorage {
  private static final int INITIAL_LANES = 8;
  private static final long[] EMPTY_LONGS = new long[0];
  private static final int[] EMPTY_INTS = new int[0];

  private long[] low = EMPTY_LONGS;
  private long[] high = EMPTY_LONGS;
  private int[] descriptors = EMPTY_INTS;
  private int[] textOffsets = EMPTY_INTS;
  private int[] textLengths = EMPTY_INTS;

  StatusCode reserve(int requested, int maximum, int used) {
    if (requested <= low.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        low.length, requested, maximum, INITIAL_LANES);
    try {
      long[] grownLow = new long[capacity];
      long[] grownHigh = new long[capacity];
      int[] grownDescriptors = new int[capacity];
      int[] grownOffsets = new int[capacity];
      int[] grownLengths = new int[capacity];
      System.arraycopy(low, 0, grownLow, 0, used);
      System.arraycopy(high, 0, grownHigh, 0, used);
      System.arraycopy(descriptors, 0, grownDescriptors, 0, used);
      System.arraycopy(textOffsets, 0, grownOffsets, 0, used);
      System.arraycopy(textLengths, 0, grownLengths, 0, used);
      low = grownLow;
      high = grownHigh;
      descriptors = grownDescriptors;
      textOffsets = grownOffsets;
      textLengths = grownLengths;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void clear(int count) {
    for (int index = 0; index < count; index++) {
      low[index] = 0;
      high[index] = 0;
      descriptors[index] = 0;
      textOffsets[index] = 0;
      textLengths[index] = 0;
    }
  }

  void publish(
      int index, int descriptor, long highValue, long lowValue,
      int textOffset, int textLength) {
    low[index] = lowValue;
    high[index] = highValue;
    descriptors[index] = descriptor;
    textOffsets[index] = textOffset;
    textLengths[index] = textLength;
  }

  int capacity() { return low.length; }
  long lowAt(int index) { return low[index]; }
  long highAt(int index) { return high[index]; }
  int descriptorAt(int index) { return descriptors[index]; }
  int textOffsetAt(int index) { return textOffsets[index]; }
  int textLengthAt(int index) { return textLengths[index]; }
}
