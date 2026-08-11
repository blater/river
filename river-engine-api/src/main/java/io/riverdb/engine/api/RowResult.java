package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;

/** Reusable bounded row result; unavailable with OK denotes end of stream. */
public final class RowResult {
  private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
  private long key;
  private long nullMask;
  private long varcharMask;
  private int columnCount;
  private boolean available;

  public void reset() {
    key = 0;
    nullMask = 0;
    varcharMask = 0;
    columnCount = 0;
    available = false;
  }

  public StatusCode complete(
      long rowKey,
      long[] sourceValues,
      long sourceNullMask,
      int columns) {
    return complete(rowKey, sourceValues, sourceNullMask, 0, columns);
  }

  public StatusCode complete(
      long rowKey,
      long[] sourceValues,
      long sourceNullMask,
      long sourceVarcharMask,
      int columns) {
    if (sourceValues == null
        || columns <= 0
        || columns > values.length
        || (sourceNullMask & ~((1L << columns) - 1)) != 0
        || (sourceVarcharMask & ~((1L << columns) - 1)) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset();
    key = rowKey;
    nullMask = sourceNullMask;
    varcharMask = sourceVarcharMask;
    columnCount = columns;
    available = true;
    for (int index = 0; index < columns; index++) {
      values[index] = sourceValues[index];
    }
    return StatusCode.OK;
  }

  public long key() {
    return key;
  }

  public int columnCount() {
    return columnCount;
  }

  public long valueAt(int index) {
    return index >= 0 && index < columnCount ? values[index] : 0;
  }

  public boolean isNull(int index) {
    return index >= 0 && index < columnCount && (nullMask & 1L << index) != 0;
  }

  public long nullMask() {
    return nullMask;
  }

  public boolean isVarchar(int index) {
    return index >= 0
        && index < columnCount
        && (varcharMask & 1L << index) != 0;
  }

  public int textLengthAt(int index) {
    return isVarchar(index) && !isNull(index)
        ? PackedText.length(valueAt(index)) : -1;
  }

  public int copyTextAt(int index, char[] destination, int offset) {
    return isVarchar(index) && !isNull(index)
        ? PackedText.copyTo(valueAt(index), destination, offset) : -1;
  }

  public long varcharMask() {
    return varcharMask;
  }

  public boolean isAvailable() {
    return available;
  }
}
