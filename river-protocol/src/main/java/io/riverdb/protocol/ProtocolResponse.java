package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Reusable decoded response whose retained storage follows the admitted shape. */
public final class ProtocolResponse implements ProtocolResponseValuesView {
  private final ProtocolResponseValues values = new ProtocolResponseValues();
  private long[] nullableWords = new long[1];
  private StatusCode status;
  private int flags;
  private int affectedRows;
  private int columnCount;
  private long commitSequence;
  private long key;
  private long rowsReturned;
  private long challengeHigh;
  private long challengeLow;
  private int nullableWordCount;

  public void reset() {
    values.reset(columnCount);
    Arrays.fill(nullableWords, 0, nullableWordCount, 0L);
    status = null;
    flags = 0;
    affectedRows = 0;
    columnCount = 0;
    commitSequence = 0;
    key = 0;
    rowsReturned = 0;
    challengeHigh = 0;
    challengeLow = 0;
    nullableWordCount = 0;
  }

  StatusCode reserve(int columns, int textBytes, int nameBytes) {
    StatusCode status = values.reserve(columns, textBytes, nameBytes);
    if (!status.isOk()) return status;
    int words = (columns + Long.SIZE - 1) >>> 6;
    if (words <= nullableWords.length) return StatusCode.OK;
    try {
      nullableWords = Arrays.copyOf(nullableWords, words);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode beginNulls(int columns) {
    return values.beginNulls(columns);
  }

  boolean nullWordAt(int word, long value) {
    return values.nullWordAt(word, value);
  }

  void beginNullable(int columns) {
    nullableWordCount = (columns + Long.SIZE - 1) >>> 6;
    Arrays.fill(nullableWords, 0, nullableWordCount, 0L);
  }

  boolean nullableWordAt(int word, long value) {
    if (word < 0 || word >= nullableWordCount) return false;
    nullableWords[word] = value;
    return true;
  }

  void complete(StatusCode responseStatus, int responseFlags, int rows, int columns,
      long committedAt, long rowKey, long returned, long nonceHigh, long nonceLow) {
    status = responseStatus;
    flags = responseFlags;
    affectedRows = rows;
    columnCount = columns;
    commitSequence = committedAt;
    key = rowKey;
    rowsReturned = returned;
    challengeHigh = nonceHigh;
    challengeLow = nonceLow;
  }

  void typeDescriptorAt(int index, int descriptor) { values.descriptor(index, descriptor); }
  void valueAt(int index, long value) { values.value(index, value); }
  void decimalHighAt(int index, long value) { values.decimalHigh(index, value); }

  boolean textAt(int index, ByteBuffer source, int offset, int length) {
    return values.textAt(index, source, offset, length);
  }

  boolean columnNameAt(int index, ByteBuffer source, int offset, int length) {
    return values.nameAt(index, source, offset, length);
  }

  public StatusCode status() { return status; }
  public int flags() { return flags; }
  public int affectedRows() { return affectedRows; }
  public int columnCount() { return columnCount; }
  public long commitSequence() { return commitSequence; }
  public long key() { return key; }
  public long rowsReturned() { return rowsReturned; }
  public long challengeHigh() { return challengeHigh; }
  public long challengeLow() { return challengeLow; }
  public long valueAt(int index) {
    return validIndex(index) && !isVarchar(index) ? values.value(index) : 0;
  }
  @Override
  public long decimalUnscaledHighAt(int index) {
    return validIndex(index) && ProtocolDecimal128.isWide(typeDescriptorAt(index))
        ? values.decimalHigh(index)
        : ProtocolResponseValuesView.super.decimalUnscaledHighAt(index);
  }
  public boolean isNull(int index) { return validIndex(index) && values.isNull(index); }
  public long nullMask() { return values.nullWord(0); }
  public long nullWord(int word) { return values.nullWord(word); }
  public int nullWordCount() { return values.nullWordCount(); }
  public int typeDescriptorAt(int index) {
    return validIndex(index) ? values.descriptor(index) : 0;
  }
  public int textLengthAt(int index) {
    return isVarchar(index) && !isNull(index) ? values.textLength(index) : -1;
  }
  public int textByteLengthAt(int index) {
    return isVarchar(index) && !isNull(index) ? values.textByteLength(index) : -1;
  }
  public int textBytesUsed() { return values.textBytesUsed(); }
  public int copyTextAt(int index, char[] destination, int offset) {
    return isVarchar(index) && !isNull(index) ? values.copyText(index, destination, offset) : -1;
  }
  public String columnName(int index) {
    return validIndex(index) ? values.name(index) : null;
  }
  public boolean columnIsNullable(int index) {
    return validIndex(index)
        && (nullableWords[index >>> 6] & 1L << (index & 63)) != 0;
  }

  private boolean validIndex(int index) { return index >= 0 && index < columnCount; }
}
