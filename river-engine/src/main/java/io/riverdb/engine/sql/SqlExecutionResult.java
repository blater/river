package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Caller-owned reusable result for one implicit-transaction SQL statement. */
public final class SqlExecutionResult {
  private final SqlResultLanes lanes = new SqlResultLanes();
  private long commitSequence;
  private long value;
  private long key;
  private int affectedRows;
  private boolean hasValue;
  private boolean transactionActive;
  private boolean emptyResult;

  public void reset() {
    lanes.reset();
    commitSequence = 0;
    value = 0;
    key = 0;
    affectedRows = 0;
    hasValue = false;
    transactionActive = false;
    emptyResult = false;
  }

  StatusCode beginProjection(
      long selectedKey, int[] descriptors, int columns, long committedAt) {
    StatusCode status = lanes.begin(descriptors, columns);
    if (!status.isOk()) {
      lanes.reset();
      return status;
    }
    key = selectedKey;
    value = 0;
    affectedRows = columns == 0 ? 0 : 1;
    hasValue = columns > 0;
    commitSequence = committedAt;
    return StatusCode.OK;
  }

  void setProjectedValue(int index, long projectedValue) {
    lanes.setValue(index, projectedValue);
    if (index == lanes.count() - 1) value = projectedValue;
  }

  void setProjectedDecimal128(int index, long high, long low) {
    lanes.setValue(index, high, low);
    if (index == lanes.count() - 1) value = low;
  }

  void setProjectedNull(int index) {
    lanes.setNull(index);
    if (index == lanes.count() - 1) value = 0;
  }

  void setUpdate(int rows, long committedAt) {
    affectedRows = rows;
    commitSequence = committedAt;
  }

  void setGeneratedKey(long generatedKey) { key = generatedKey; }

  void markEmptyResult() { emptyResult = true; }

  void setProjection(
      long selectedKey,
      long[] projectedValues,
      long projectedNullMask,
      int[] descriptors,
      int columns,
      long committedAt) {
    if (columns > Long.SIZE) {
      reset();
      return;
    }
    StatusCode status = beginProjection(selectedKey, descriptors, columns, committedAt);
    for (int index = 0; status.isOk() && index < columns; index++) {
      if ((projectedNullMask >>> index & 1) != 0) setProjectedNull(index);
      else setProjectedValue(index, projectedValues[index]);
    }
    if (!status.isOk()) reset();
  }

  void setCommitSequence(long committedAt) { commitSequence = committedAt; }

  StatusCode setUtf8At(int index, HeapRowResult source, int offset, int length) {
    return lanes.setUtf8(index, source, offset, length);
  }

  StatusCode setTextAt(int index, char[] source, int length) {
    return lanes.setText(index, source, 0, length);
  }

  StatusCode setUtf8At(int index, ByteBuffer source, int offset, int length) {
    return lanes.setUtf8(index, source, offset, length);
  }

  StatusCode setScalar(long scalar, long committedAt) {
    return setTypedScalar(scalar, SqlTypeDescriptor.BIGINT, committedAt);
  }

  StatusCode setTypedScalar(long scalar, int descriptor, long committedAt) {
    return setTypedScalar(scalar >> 63, scalar, descriptor, committedAt);
  }

  StatusCode setTypedScalar(
      long high, long scalar, int descriptor, long committedAt) {
    StatusCode status = lanes.beginSingle(descriptor);
    if (!status.isOk()) {
      lanes.reset();
      return status;
    }
    key = 0;
    affectedRows = 1;
    hasValue = true;
    commitSequence = committedAt;
    if (SqlTypeDescriptor.isWideDecimal(descriptor)) {
      setProjectedDecimal128(0, high, scalar);
    } else {
      setProjectedValue(0, scalar);
    }
    return StatusCode.OK;
  }

  void setTransaction(boolean active, long committedAt) {
    transactionActive = active;
    commitSequence = committedAt;
  }

  public int affectedRows() { return affectedRows; }
  public boolean hasValue() { return hasValue; }
  public long value() { return value; }
  /** Legacy scalar row-key field; descriptor composite/keyless rows report zero. */
  public long key() { return key; }
  public int columnCount() { return lanes.count(); }
  public long valueAt(int index) { return lanes.value(index); }
  public long highValueAt(int index) { return lanes.highValue(index); }
  public boolean isNull(int index) { return lanes.isNull(index); }
  public long nullMask() { return lanes.nullWord(0); }
  public long nullWord(int word) { return lanes.nullWord(word); }
  public int nullWordCount() { return lanes.nullWordCount(); }
  public boolean isVarchar(int index) { return lanes.isText(index); }
  public int typeDescriptorAt(int index) { return lanes.descriptor(index); }
  public int textLengthAt(int index) { return lanes.textLength(index); }
  public int copyTextAt(int index, char[] target, int offset) {
    return lanes.copyText(index, target, offset);
  }
  public char textCharacterAt(int index, int character) {
    return lanes.textCharacter(index, character);
  }
  public long commitSequence() { return commitSequence; }
  public boolean transactionActive() { return transactionActive; }
  public boolean isEmptyResult() { return emptyResult; }
}
