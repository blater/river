package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;

/** Reusable bounded result for one command or query close. */
public final class CommandResult {
  public static final int MAXIMUM_COLUMNS = 8;
  public static final int MAXIMUM_TEXT_CHARACTERS = 64;

  private final long[] values = new long[MAXIMUM_COLUMNS];
  private final char[][] textValues =
      new char[MAXIMUM_COLUMNS][MAXIMUM_TEXT_CHARACTERS];
  private final int[] textLengths = new int[MAXIMUM_COLUMNS];
  private long commitSequence;
  private long key;
  private long nullMask;
  private long varcharMask;
  private int affectedRows;
  private int columnCount;
  private boolean rowAvailable;
  private boolean transactionActive;

  public void reset() {
    commitSequence = 0;
    key = 0;
    nullMask = 0;
    varcharMask = 0;
    affectedRows = 0;
    columnCount = 0;
    rowAvailable = false;
    transactionActive = false;
    for (int index = 0; index < textLengths.length; index++) {
      textLengths[index] = 0;
    }
  }

  public StatusCode complete(
      int rows,
      long committedAt,
      boolean activeTransaction,
      boolean hasRow,
      long selectedKey,
      long[] sourceValues,
      long sourceNullMask,
      int columns) {
    return complete(
        rows,
        committedAt,
        activeTransaction,
        hasRow,
        selectedKey,
        sourceValues,
        sourceNullMask,
        0,
        columns);
  }

  public StatusCode complete(
      int rows,
      long committedAt,
      boolean activeTransaction,
      boolean hasRow,
      long selectedKey,
      long[] sourceValues,
      long sourceNullMask,
      long sourceVarcharMask,
      int columns) {
    if (rows < 0
        || committedAt < 0
        || columns < 0
        || columns > values.length
        || hasRow != (columns > 0)
        || columns > 0 && sourceValues == null
        || (sourceNullMask & ~((1L << columns) - 1)) != 0
        || (sourceVarcharMask & ~((1L << columns) - 1)) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset();
    affectedRows = rows;
    commitSequence = committedAt;
    transactionActive = activeTransaction;
    rowAvailable = hasRow;
    key = selectedKey;
    nullMask = sourceNullMask;
    varcharMask = sourceVarcharMask;
    columnCount = columns;
    for (int index = 0; index < columns; index++) {
      values[index] = sourceValues[index];
      if ((sourceVarcharMask & 1L << index) != 0
          && (sourceNullMask & 1L << index) == 0) {
        textLengths[index] = PackedText.copyTo(
            sourceValues[index], textValues[index], 0);
      }
    }
    return StatusCode.OK;
  }

  public StatusCode setTextAt(
      int index,
      char[] source,
      int offset,
      int length) {
    if (!rowAvailable
        || index < 0
        || index >= columnCount
        || source == null
        || offset < 0
        || length < 0
        || length > MAXIMUM_TEXT_CHARACTERS
        || offset > source.length - length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int character = 0; character < length; character++) {
      char value = source[offset + character];
      if (value < 0x20 || value > 0x7e) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      textValues[index][character] = value;
    }
    textLengths[index] = length;
    varcharMask |= 1L << index;
    return StatusCode.OK;
  }

  public int affectedRows() {
    return affectedRows;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public boolean transactionActive() {
    return transactionActive;
  }

  public boolean rowAvailable() {
    return rowAvailable;
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
        ? textLengths[index] : -1;
  }

  public int copyTextAt(int index, char[] destination, int offset) {
    int length = textLengthAt(index);
    if (length < 0
        || destination == null
        || offset < 0
        || offset > destination.length - length) {
      return -1;
    }
    System.arraycopy(textValues[index], 0, destination, offset, length);
    return length;
  }

  public char textCharacterAt(int index, int character) {
    return isVarchar(index)
            && !isNull(index)
            && character >= 0
            && character < textLengths[index]
        ? textValues[index][character] : 0;
  }

  public long varcharMask() {
    return varcharMask;
  }
}
