package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Reusable bounded row result; unavailable with OK denotes end of stream. */
public final class RowResult {
  private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
  private final char[][] textValues =
      new char[CommandResult.MAXIMUM_COLUMNS][CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final int[] textLengths = new int[CommandResult.MAXIMUM_COLUMNS];
  private final int[] typeDescriptors = new int[CommandResult.MAXIMUM_COLUMNS];
  private long key;
  private long nullMask;
  private int columnCount;
  private boolean available;

  public void reset() {
    key = 0;
    nullMask = 0;
    columnCount = 0;
    available = false;
    for (int index = 0; index < textLengths.length; index++) {
      textLengths[index] = 0;
      typeDescriptors[index] = 0;
    }
  }

  public StatusCode complete(
      long rowKey,
      long[] sourceValues,
      long sourceNullMask,
      int[] sourceTypeDescriptors,
      int columns) {
    if (sourceValues == null
        || sourceTypeDescriptors == null
        || columns <= 0
        || columns > values.length
        || columns > sourceTypeDescriptors.length
        || (sourceNullMask & ~((1L << columns) - 1)) != 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < columns; index++) {
      if (!SqlTypeDescriptor.isValid(sourceTypeDescriptors[index])) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    reset();
    key = rowKey;
    nullMask = sourceNullMask;
    columnCount = columns;
    available = true;
    for (int index = 0; index < columns; index++) {
      values[index] = sourceValues[index];
      typeDescriptors[index] = sourceTypeDescriptors[index];
      if (isVarchar(index) && (sourceNullMask & 1L << index) == 0) {
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
    if (!available
        || index < 0
        || index >= columnCount
        || source == null
        || offset < 0
        || length < 0
        || length > CommandResult.MAXIMUM_TEXT_CHARACTERS
        || offset > source.length - length
        || !isVarchar(index)) {
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
        && SqlTypeDescriptor.typeId(typeDescriptors[index])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  public int typeDescriptorAt(int index) {
    return index >= 0 && index < columnCount ? typeDescriptors[index] : 0;
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

  public boolean isAvailable() {
    return available;
  }
}
