package io.riverdb.engine.sql;

import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporalCast;

/** Reusable actual-count row projection with lazily owned generated text. */
final class SqlProjectedRow implements SqlNullWords {
  static final int MAXIMUM_GENERATED_TEXT = LocalTemporalCast.MAXIMUM_TEXT_CHARACTERS;
  private static final char[] EMPTY_TEXT = new char[0];

  final SqlRetainedArrayAllocator allocator;
  final ColumnBitSet nulls = new ColumnBitSet();
  long[] highs = new long[0];
  long[] values = new long[0];
  char[][] text = new char[0][];
  int[] textLengths = new int[0];
  private int count;
  private StatusCode status = StatusCode.OK;

  SqlProjectedRow() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlProjectedRow(SqlRetainedArrayAllocator retainedAllocator) {
    allocator = retainedAllocator;
  }

  StatusCode reserve(int projections) {
    return SqlProjectedRowAdmission.reserve(this, projections);
  }

  void reset(int projectionCount) {
    status = reserve(projectionCount);
    if (!status.isOk()) return;
    for (int index = 0; index < count; index++) {
      highs[index] = 0;
      values[index] = 0;
      textLengths[index] = 0;
    }
    status = nulls.clearForSize(projectionCount);
    if (status.isOk()) count = projectionCount;
  }

  void setValue(int index, long value) {
    highs[index] = value >> 63;
    values[index] = value;
  }

  void setDecimal128(int index, long high, long low) {
    highs[index] = high;
    values[index] = low;
  }

  void setNull(int index) {
    highs[index] = 0;
    values[index] = 0;
    nulls.set(index);
  }

  void setText(int index, char[] source, int length) {
    setText(index, source, 0, length);
  }

  void setText(int index, char[] source, int offset, int length) {
    char[] target = writableText(index);
    if (target.length < length) return;
    System.arraycopy(source, offset, target, 0, length);
    textLengths[index] = length;
  }

  StatusCode status() { return status; }
  int count() { return count; }
  long[] highs() { return highs; }
  long[] values() { return values; }
  long highValue(int index) { return highs[index]; }
  long value(int index) { return values[index]; }
  boolean isNull(int index) { return nulls.get(index); }
  @Override public long nullWord(int word) { return nulls.word(word); }
  @Override public int nullWordCount() { return nulls.wordCount(); }
  long nullMask() { return nulls.word(0); }
  int textLength(int index) { return textLengths[index]; }
  char textCharacter(int projection, int index) { return text[projection][index]; }
  char[] text(int projection) { return writableText(projection); }

  StatusCode prepareText(int projection) {
    StatusCode admitted = SqlProjectedRowAdmission.prepareText(this, projection);
    if (!admitted.isOk()) status = admitted;
    return admitted;
  }

  private char[] writableText(int projection) {
    return prepareText(projection).isOk() ? text[projection] : EMPTY_TEXT;
  }
}
