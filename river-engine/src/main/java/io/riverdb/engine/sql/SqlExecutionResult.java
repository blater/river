package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Caller-owned result for one implicit-transaction SQL statement. */
public final class SqlExecutionResult {
  private final long[] values = new long[TableSchema.MAXIMUM_COLUMNS];
  private final int[] typeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  private final char[][] textValues =
      new char[TableSchema.MAXIMUM_COLUMNS][CommandResult.MAXIMUM_TEXT_CHARACTERS];
  private final int[] textLengths = new int[TableSchema.MAXIMUM_COLUMNS];
  private long commitSequence;
  private long value;
  private long key;
  private long nullMask;
  private int affectedRows;
  private int columnCount;
  private boolean hasValue;
  private boolean transactionActive;

  public void reset() {
    for (int index = 0; index < columnCount; index++) {
      typeDescriptors[index] = 0;
      textLengths[index] = 0;
    }
    commitSequence = 0;
    value = 0;
    key = 0;
    nullMask = 0;
    affectedRows = 0;
    columnCount = 0;
    hasValue = false;
    transactionActive = false;
  }

  void setUpdate(int rows, long committedAt) {
    affectedRows = rows;
    commitSequence = committedAt;
  }

  void setGeneratedKey(long generatedKey) {
    key = generatedKey;
  }

  void setProjection(
      long selectedKey,
      long[] projectedValues,
      long projectedNullMask,
      int[] projectedTypeDescriptors,
      int projectedColumnCount,
      long committedAt) {
    key = selectedKey;
    nullMask = projectedNullMask;
    columnCount = projectedColumnCount;
    for (int index = 0; index < projectedColumnCount; index++) {
      values[index] = projectedValues[index];
      typeDescriptors[index] = projectedTypeDescriptors[index];
    }
    value = projectedColumnCount == 0 ? 0 : values[projectedColumnCount - 1];
    hasValue = projectedColumnCount > 0;
    affectedRows = 1;
    commitSequence = committedAt;
  }

  void setCommitSequence(long committedAt) {
    commitSequence = committedAt;
  }

  StatusCode setUtf8At(
      int index,
      HeapRowResult source,
      int offset,
      int length) {
    if (index < 0
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

  StatusCode setTextAt(int index, char[] source, int length) {
    if (index < 0
        || index >= columnCount
        || source == null
        || length < 0
        || length > source.length
        || length > textValues[index].length
        || !isVarchar(index)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    System.arraycopy(source, 0, textValues[index], 0, length);
    textLengths[index] = length;
    return StatusCode.OK;
  }

  StatusCode setUtf8At(int index, ByteBuffer source, int offset, int length) {
    if (index < 0
        || index >= columnCount
        || source == null
        || offset < 0
        || length < 0
        || !isVarchar(index)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int chars = Utf8Text.decode(
        source, offset, length, textValues[index], 0);
    if (chars < 0) {
      return StatusCode.CORRUPTION;
    }
    textLengths[index] = chars;
    return StatusCode.OK;
  }

  void setScalar(long scalar, long committedAt) {
    setTypedScalar(scalar, SqlTypeDescriptor.BIGINT, committedAt);
  }

  void setTypedScalar(long scalar, int descriptor, long committedAt) {
    values[0] = scalar;
    value = scalar;
    key = 0;
    nullMask = 0;
    typeDescriptors[0] = descriptor;
    affectedRows = 1;
    columnCount = 1;
    hasValue = true;
    commitSequence = committedAt;
  }

  void setTransaction(boolean active, long committedAt) {
    transactionActive = active;
    commitSequence = committedAt;
  }

  public int affectedRows() {
    return affectedRows;
  }

  public boolean hasValue() {
    return hasValue;
  }

  public long value() {
    return value;
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
    return isVarchar(index) && !isNull(index) ? textLengths[index] : -1;
  }

  public int copyTextAt(int index, char[] target, int offset) {
    int length = textLengthAt(index);
    if (length < 0
        || target == null
        || offset < 0
        || offset > target.length - length) {
      return -1;
    }
    System.arraycopy(textValues[index], 0, target, offset, length);
    return length;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public boolean transactionActive() {
    return transactionActive;
  }
}
