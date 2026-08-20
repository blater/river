package io.riverdb.engine.sql;

import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.relational.TableSchema;

/** Synchronous evaluator scratch; retained boundaries immediately encode canonical UTF-8. */
final class SqlBlockRow {
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private final char[][] text = new char[TableSchema.MAXIMUM_COLUMNS][];
  private final short[] textLengths = new short[TableSchema.MAXIMUM_COLUMNS];
  private long nullMask;
  private int count;

  void reset(int columns) {
    for (int column = 0; column < count; column++) {
      values[column] = 0;
      int length = Short.toUnsignedInt(textLengths[column]);
      if (text[column] != null) {
        for (int index = 0; index < length; index++) text[column][index] = 0;
      }
      textLengths[column] = 0;
    }
    count = columns;
    nullMask = 0;
  }

  void setValue(int column, long value) { values[column] = value; }
  void setNull(int column) {
    values[column] = 0;
    nullMask |= 1L << column;
  }
  void setText(int column, char[] source, int offset, int length) {
    char[] target = text(column);
    System.arraycopy(source, offset, target, 0, length);
    textLengths[column] = (short) length;
  }
  void setTextLength(int column, int length) {
    textLengths[column] = (short) length;
  }
  void copyFrom(SqlBlockRow source) {
    reset(source.count);
    nullMask = source.nullMask;
    for (int column = 0; column < count; column++) {
      values[column] = source.values[column];
      int length = Short.toUnsignedInt(source.textLengths[column]);
      if (length > 0) {
        System.arraycopy(source.text[column], 0, text(column), 0, length);
      }
      textLengths[column] = (short) length;
    }
  }
  long value(int column) { return values[column]; }
  boolean nullValue(int column) { return (nullMask & 1L << column) != 0; }
  long nullMask() { return nullMask; }
  int count() { return count; }
  int textLength(int column) { return Short.toUnsignedInt(textLengths[column]); }
  char[] text(int column) {
    if (text[column] == null) {
      text[column] = new char[CommandResult.MAXIMUM_TEXT_CHARACTERS];
    }
    return text[column];
  }
  char textCharacter(int column, int index) { return text[column][index]; }
}
