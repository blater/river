package io.riverdb.engine.sql;

import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.engine.relational.TableSchema;

/** Reusable primitive row projection with bounded generated temporal text. */
final class SqlProjectedRow {
  static final int MAXIMUM_GENERATED_TEXT =
      LocalTemporalCast.MAXIMUM_TEXT_CHARACTERS;

  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private final char[][] text =
      new char[TableSchema.MAXIMUM_COLUMNS][MAXIMUM_GENERATED_TEXT];
  private final byte[] textLengths = new byte[TableSchema.MAXIMUM_COLUMNS];
  private long nullMask;
  private int count;

  void reset(int projectionCount) {
    for (int index = 0; index < count; index++) {
      values[index] = 0;
      textLengths[index] = 0;
    }
    count = projectionCount;
    nullMask = 0;
  }

  void setValue(int index, long value) {
    values[index] = value;
  }

  void setNull(int index) {
    values[index] = 0;
    nullMask |= 1L << index;
  }

  void setText(int index, char[] source, int length) {
    System.arraycopy(source, 0, text[index], 0, length);
    textLengths[index] = (byte) length;
  }

  long[] values() {
    return values;
  }

  long value(int index) {
    return values[index];
  }

  long nullMask() {
    return nullMask;
  }

  int textLength(int index) {
    return Byte.toUnsignedInt(textLengths[index]);
  }

  char textCharacter(int projection, int index) {
    return text[projection][index];
  }

  char[] text(int projection) {
    return text[projection];
  }
}
