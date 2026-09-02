package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.text.Utf8Text;

/** Reusable bounded result for one command or query close. */
public final class CommandResult {
  public static final int MAXIMUM_COLUMNS = SqlShapeLimits.MAX_RESULT_COLUMNS;
  /** Public result scratch bound; independent of a column's declared VARCHAR width. */
  public static final int MAXIMUM_TEXT_CHARACTERS = Utf8Text.MAXIMUM_BUFFER_CHARACTERS;

  private final PublicResultValues values;
  private long commitSequence;
  private long key;
  private int affectedRows;
  private int columnCount;
  private boolean rowAvailable;
  private boolean transactionActive;

  public CommandResult() {
    this(RetainedMemoryLease.unbounded());
  }

  public CommandResult(RetainedMemoryLease retainedMemory) {
    values = new PublicResultValues(retainedMemory);
  }

  public void reset() {
    commitSequence = 0;
    key = 0;
    affectedRows = 0;
    columnCount = 0;
    rowAvailable = false;
    transactionActive = false;
    values.reset();
  }

  public StatusCode complete(
      int rows,
      long committedAt,
      boolean activeTransaction,
      boolean hasRow,
      long selectedKey,
      long[] sourceValues,
      long sourceNullMask,
      int[] sourceTypeDescriptors,
      int columns) {
    if (rows < 0
        || committedAt < 0
        || columns < 0
        || hasRow != (columns > 0)
        || columns > Long.SIZE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = values.beginLegacy(
        sourceValues, sourceNullMask, sourceTypeDescriptors, columns);
    if (!status.isOk()) return status;
    affectedRows = rows;
    commitSequence = committedAt;
    transactionActive = activeTransaction;
    rowAvailable = hasRow;
    key = selectedKey;
    columnCount = columns;
    return StatusCode.OK;
  }

  public StatusCode complete(
      int rows,
      long committedAt,
      boolean activeTransaction,
      boolean hasRow,
      long selectedKey,
      long[] sourceDecimalHighValues,
      long[] sourceValues,
      long[] sourceNullWords,
      int sourceNullWordCount,
      int[] sourceTypeDescriptors,
      int columns) {
    if (rows < 0 || committedAt < 0 || hasRow != (columns > 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = values.begin(
        sourceDecimalHighValues, sourceValues, sourceNullWords,
        sourceNullWordCount, sourceTypeDescriptors, columns);
    if (!status.isOk()) return status;
    affectedRows = rows;
    commitSequence = committedAt;
    transactionActive = activeTransaction;
    rowAvailable = hasRow;
    key = selectedKey;
    columnCount = columns;
    return StatusCode.OK;
  }

  public StatusCode complete(
      int rows,
      long committedAt,
      boolean activeTransaction,
      boolean hasRow,
      long selectedKey,
      long[] sourceValues,
      long[] sourceNullWords,
      int sourceNullWordCount,
      int[] sourceTypeDescriptors,
      int columns) {
    if (rows < 0 || committedAt < 0 || hasRow != (columns > 0)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = values.begin(
        sourceValues, sourceNullWords, sourceNullWordCount, sourceTypeDescriptors, columns);
    if (!status.isOk()) return status;
    affectedRows = rows;
    commitSequence = committedAt;
    transactionActive = activeTransaction;
    rowAvailable = hasRow;
    key = selectedKey;
    columnCount = columns;
    return StatusCode.OK;
  }

  public StatusCode reserve(int columns, int textBytes) {
    return values.reserve(columns, textBytes);
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
        || length > MAXIMUM_TEXT_CHARACTERS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return values.setText(index, source, offset, length);
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
    return values.valueAt(index);
  }

  public short smallintAt(int index) {
    return PublicNumericValue.smallint(typeDescriptorAt(index), valueAt(index));
  }

  public int integerAt(int index) {
    return PublicNumericValue.integer(typeDescriptorAt(index), valueAt(index));
  }

  public long bigintAt(int index) {
    return PublicNumericValue.bigint(typeDescriptorAt(index), valueAt(index));
  }

  public long decimalUnscaledAt(int index) {
    return PublicNumericValue.decimal(typeDescriptorAt(index), valueAt(index));
  }

  public long decimalUnscaledHighAt(int index) {
    return PublicNumericValue.decimalHigh(
        typeDescriptorAt(index), values.decimalHighAt(index), valueAt(index));
  }

  public long decimalUnscaledLowAt(int index) {
    return PublicNumericValue.decimalLow(typeDescriptorAt(index), valueAt(index));
  }

  public float realAt(int index) {
    return PublicNumericValue.real(typeDescriptorAt(index), valueAt(index));
  }

  public double doubleAt(int index) {
    return PublicNumericValue.doubleValue(typeDescriptorAt(index), valueAt(index));
  }

  public boolean isNull(int index) {
    return values.isNull(index);
  }

  public long nullMask() {
    return values.nullWord(0);
  }

  public long nullWord(int word) {
    return values.nullWord(word);
  }

  public int nullWordCount() {
    return values.nullWordCount();
  }

  public boolean isVarchar(int index) {
    return values.isText(index);
  }

  public int typeDescriptorAt(int index) {
    return values.descriptorAt(index);
  }

  public int textLengthAt(int index) {
    return values.textLengthAt(index);
  }

  public int copyTextAt(int index, char[] destination, int offset) {
    int length = textLengthAt(index);
    if (length < 0
        || destination == null
        || offset < 0
        || offset > destination.length - length) {
      return -1;
    }
    return values.copyTextAt(index, destination, offset);
  }

  public char textCharacterAt(int index, int character) {
    return values.textCharacterAt(index, character);
  }

  public StatusCode releaseHighWater() {
    reset();
    return values.releaseHighWater();
  }

  public StatusCode release() {
    reset();
    return values.release();
  }

  public long retainedBytes() { return values.retainedBytes(); }
  public static long maximumRetainedBytes() { return PublicResultValues.maximumRetainedBytes(); }
  public static long retainedFloorBytes() { return PublicResultValues.retainedFloorBytes(); }

}
