package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import java.nio.ByteBuffer;

/** Reusable decoded response with at most the engine API's bounded value count. */
public final class ProtocolResponse {
  private final long[] values = new long[CommandResult.MAXIMUM_COLUMNS];
  private final char[][] textValues =
      new char[CommandResult.MAXIMUM_COLUMNS][CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final int[] textLengths = new int[CommandResult.MAXIMUM_COLUMNS];
  private final int[] typeDescriptors = new int[CommandResult.MAXIMUM_COLUMNS];
  private final char[][] columnNames =
      new char[CommandResult.MAXIMUM_COLUMNS][ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES];
  private final int[] columnNameLengths = new int[CommandResult.MAXIMUM_COLUMNS];
  private StatusCode status;
  private int flags;
  private int affectedRows;
  private int columnCount;
  private long commitSequence;
  private long key;
  private long rowsReturned;
  private long challengeHigh;
  private long challengeLow;
  private long nullMask;

  public void reset() {
    status = null;
    flags = 0;
    affectedRows = 0;
    columnCount = 0;
    commitSequence = 0;
    key = 0;
    rowsReturned = 0;
    challengeHigh = 0;
    challengeLow = 0;
    nullMask = 0;
    for (int index = 0; index < columnNameLengths.length; index++) {
      columnNameLengths[index] = 0;
      textLengths[index] = 0;
      typeDescriptors[index] = 0;
    }
  }

  void complete(
      StatusCode responseStatus,
      int responseFlags,
      int rows,
      int columns,
      long committedAt,
      long rowKey,
      long returned,
      long nonceHigh,
      long nonceLow,
      long responseNullMask) {
    status = responseStatus;
    flags = responseFlags;
    affectedRows = rows;
    columnCount = columns;
    commitSequence = committedAt;
    key = rowKey;
    rowsReturned = returned;
    challengeHigh = nonceHigh;
    challengeLow = nonceLow;
    nullMask = responseNullMask;
  }

  void typeDescriptorAt(int index, int descriptor) {
    typeDescriptors[index] = descriptor;
  }

  void valueAt(int index, long value) {
    values[index] = value;
  }

  boolean textAt(int index, ByteBuffer source, int offset, int length) {
    int chars = Utf8Text.decode(source, offset, length, textValues[index], 0);
    textLengths[index] = Math.max(0, chars);
    return chars >= 0;
  }

  void columnNameAt(int index, ByteBuffer source, int offset, int length) {
    for (int character = 0; character < length; character++) {
      columnNames[index][character] = (char) (source.get(offset + character) & 0xff);
    }
    columnNameLengths[index] = length;
  }

  public StatusCode status() {
    return status;
  }

  public int flags() {
    return flags;
  }

  public boolean rowAvailable() {
    return (flags & ProtocolFrameCodec.FLAG_ROW_AVAILABLE) != 0;
  }

  public boolean transactionActive() {
    return (flags & ProtocolFrameCodec.FLAG_TRANSACTION_ACTIVE) != 0;
  }

  public boolean queryActive() {
    return (flags & ProtocolFrameCodec.FLAG_QUERY_ACTIVE) != 0;
  }

  public int affectedRows() {
    return affectedRows;
  }

  public int columnCount() {
    return columnCount;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public long key() {
    return key;
  }

  public long rowsReturned() {
    return rowsReturned;
  }

  public long challengeHigh() {
    return challengeHigh;
  }

  public long challengeLow() {
    return challengeLow;
  }

  public long valueAt(int index) {
    return index >= 0 && index < columnCount && !isVarchar(index)
        ? values[index] : 0;
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

  public String columnName(int index) {
    return index >= 0 && index < columnCount && columnNameLengths[index] > 0
        ? new String(columnNames[index], 0, columnNameLengths[index]) : null;
  }
}
