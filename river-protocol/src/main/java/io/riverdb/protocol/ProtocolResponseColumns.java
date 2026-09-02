package io.riverdb.protocol;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Geometrically retained primitive lanes for a decoded response. */
final class ProtocolResponseColumns {
  private final ColumnBitSet nulls = new ColumnBitSet();
  private long[] values = new long[0];
  private long[] decimalHighs = new long[0];
  private int[][] lanes = new int[5][0];

  StatusCode reserve(int count) {
    StatusCode status = nulls.reserve(count, SqlShapeLimits.MAX_RESULT_COLUMNS);
    if (!status.isOk() || count <= values.length) return status;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, count, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    try {
      long[] grownValues = new long[capacity];
      long[] grownDecimalHighs = new long[capacity];
      int[][] grown = new int[5][capacity];
      System.arraycopy(values, 0, grownValues, 0, values.length);
      System.arraycopy(decimalHighs, 0, grownDecimalHighs, 0, decimalHighs.length);
      for (int lane = 0; lane < lanes.length; lane++) {
        System.arraycopy(lanes[lane], 0, grown[lane], 0, lanes[lane].length);
      }
      values = grownValues;
      decimalHighs = grownDecimalHighs;
      lanes = grown;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void reset(int count) {
    for (int index = 0; index < count; index++) {
      values[index] = 0;
      decimalHighs[index] = 0;
      for (int lane = 0; lane < lanes.length; lane++) lanes[lane][index] = 0;
    }
    nulls.reset();
  }

  StatusCode beginNulls(int count) { return nulls.clearForSize(count); }
  boolean nullWordAt(int word, long value) { return nulls.setWord(word, value); }
  boolean isNull(int index) { return nulls.get(index); }
  long nullWord(int word) { return nulls.word(word); }
  int nullWordCount() { return nulls.wordCount(); }
  long value(int index) { return values[index]; }
  void value(int index, long value) { values[index] = value; }
  long decimalHigh(int index) { return decimalHighs[index]; }
  void decimalHigh(int index, long value) { decimalHighs[index] = value; }
  int lane(int lane, int index) { return lanes[lane][index]; }
  void lane(int lane, int index, int value) { lanes[lane][index] = value; }
}
