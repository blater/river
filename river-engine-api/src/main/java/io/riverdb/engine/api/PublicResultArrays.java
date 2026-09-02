package io.riverdb.engine.api;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Geometrically grown primitive lane storage for one reusable public result. */
final class PublicResultArrays {
  private static final int INITIAL_LANES = 8;
  private static final long[] EMPTY_LONGS = new long[0];
  private static final int[] EMPTY_INTS = new int[0];

  private long[] values = EMPTY_LONGS;
  private long[] decimalHighs = EMPTY_LONGS;
  private int[] descriptors = EMPTY_INTS;
  private int[] textOffsets = EMPTY_INTS;
  private int[] textLengths = EMPTY_INTS;

  StatusCode reserve(int columns, int used) {
    if (columns <= values.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, columns, SqlShapeLimits.MAX_RESULT_COLUMNS, INITIAL_LANES);
    try {
      long[] newValues = new long[capacity];
      long[] newDecimalHighs = new long[capacity];
      int[] newDescriptors = new int[capacity];
      int[] newTextOffsets = new int[capacity];
      int[] newTextLengths = new int[capacity];
      System.arraycopy(values, 0, newValues, 0, used);
      System.arraycopy(decimalHighs, 0, newDecimalHighs, 0, used);
      System.arraycopy(descriptors, 0, newDescriptors, 0, used);
      System.arraycopy(textOffsets, 0, newTextOffsets, 0, used);
      System.arraycopy(textLengths, 0, newTextLengths, 0, used);
      values = newValues;
      decimalHighs = newDecimalHighs;
      descriptors = newDescriptors;
      textOffsets = newTextOffsets;
      textLengths = newTextLengths;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void reset(int used) {
    for (int index = 0; index < used; index++) {
      values[index] = 0;
      decimalHighs[index] = 0;
      descriptors[index] = 0;
      textOffsets[index] = 0;
      textLengths[index] = 0;
    }
  }

  int capacity() { return values.length; }

  void release() {
    values = EMPTY_LONGS;
    decimalHighs = EMPTY_LONGS;
    descriptors = EMPTY_INTS;
    textOffsets = EMPTY_INTS;
    textLengths = EMPTY_INTS;
  }

  void copy(long[] sourceValues, int[] sourceDescriptors, int columns) {
    for (int index = 0; index < columns; index++) {
      values[index] = sourceValues[index];
      decimalHighs[index] = PublicDecimal128.inferredHigh(
          sourceValues[index], sourceDescriptors[index]);
      descriptors[index] = sourceDescriptors[index];
    }
  }

  void copy(
      long[] sourceHighs, long[] sourceValues, int[] sourceDescriptors, int columns) {
    for (int index = 0; index < columns; index++) {
      values[index] = sourceValues[index];
      decimalHighs[index] = sourceHighs[index];
      descriptors[index] = sourceDescriptors[index];
    }
  }

  long value(int index) { return values[index]; }
  long decimalHigh(int index) { return decimalHighs[index]; }

  int descriptor(int index) { return descriptors[index]; }

  int textOffset(int index) { return textOffsets[index]; }

  int textLength(int index) { return textLengths[index]; }

  void text(int index, int offset, int length) {
    textOffsets[index] = offset;
    textLengths[index] = length;
  }
}
