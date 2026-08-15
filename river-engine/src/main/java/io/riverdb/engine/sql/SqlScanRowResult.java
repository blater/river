package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.TableSchema;

/** Caller-owned decoded `KEY`, `VALUE` row returned by an SQL scan. */
public final class SqlScanRowResult {
  private final RelationalScanResult relational = new RelationalScanResult();
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private final char[][] textValues =
      new char[TableSchema.MAXIMUM_COLUMNS][CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final int[] textLengths = new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] typeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  private long key;
  private long value;
  private long nullMask;
  private int columnCount;
  private boolean available;

  public void reset() {
    relational.reset();
    key = 0;
    value = 0;
    nullMask = 0;
    columnCount = 0;
    available = false;
    for (int index = 0; index < textLengths.length; index++) {
      textLengths[index] = 0;
      typeDescriptors[index] = 0;
    }
  }

  RelationalScanResult relational() {
    return relational;
  }

  void set(
      long rowKey,
      long[] projectedValues,
      long projectedNullMask,
      int[] projectedTypeDescriptors,
      int projectedColumnCount) {
    key = rowKey;
    columnCount = projectedColumnCount;
    nullMask = projectedNullMask;
    for (int index = 0; index < projectedColumnCount; index++) {
      values[index] = projectedValues[index];
      typeDescriptors[index] = projectedTypeDescriptors[index];
      textLengths[index] = 0;
    }
    value = projectedColumnCount == 0 ? 0 : values[projectedColumnCount - 1];
    available = true;
  }

  StatusCode setTextAt(int index, CharSequence source) {
    if (!available
        || index < 0
        || index >= columnCount
        || source == null
        || source.length() > CommandResult.MAXIMUM_TEXT_CHARACTERS
        || !isVarchar(index)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int character = 0; character < source.length(); character++) {
      char value = source.charAt(character);
      if (value < 0x20 || value > 0x7e) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      textValues[index][character] = value;
    }
    textLengths[index] = source.length();
    return StatusCode.OK;
  }

  StatusCode setTextAt(int index, char[] source, int length) {
    return setTextAt(index, source, 0, length);
  }

  StatusCode setTextAt(int index, char[] source, int offset, int length) {
    if (!available
        || index < 0
        || index >= columnCount
        || source == null
        || offset < 0
        || length < 0
        || offset > source.length - length
        || length > CommandResult.MAXIMUM_TEXT_CHARACTERS
        || !isVarchar(index)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    System.arraycopy(source, offset, textValues[index], 0, length);
    textLengths[index] = length;
    return StatusCode.OK;
  }

  StatusCode setPackedTextAt(int index, long packed) {
    if (!available || index < 0 || index >= columnCount || !isVarchar(index)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int length = PackedText.length(packed);
    for (int character = 0; character < length; character++) {
      textValues[index][character] = PackedText.charAt(packed, character);
    }
    textLengths[index] = length;
    return StatusCode.OK;
  }

  StatusCode setUtf8At(
      int index,
      io.riverdb.storage.heap.HeapRowResult source,
      int offset,
      int length) {
    if (!available
        || index < 0
        || index >= columnCount
        || source == null
        || offset < 0
        || length < 0
        || !isVarchar(index)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    textLengths[index] = Utf8RowText.decode(
        source, offset, length, textValues[index]);
    return StatusCode.OK;
  }

  public long key() {
    return key;
  }

  public long value() {
    return value;
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
    return isVarchar(index) && !isNull(index) ? textLengths[index] : -1;
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

  public boolean isAvailable() {
    return available;
  }
}
