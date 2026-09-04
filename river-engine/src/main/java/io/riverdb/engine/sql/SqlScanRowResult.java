package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.PackedText;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanResult;

/** Caller-owned reusable decoded SQL scan row. */
public final class SqlScanRowResult {
  private final RelationalScanResult relational = new RelationalScanResult();
  private final SqlResultLanes lanes = new SqlResultLanes();
  private char[] pendingText = new char[0];
  private long key;
  private long value;
  private int pendingTextIndex = -1;
  private int pendingTextLength;
  private boolean available;

  StatusCode admit(
      SqlScanCursor cursor,
      SqlQueryExecution owner,
      long generation,
      SqlPhysicalPlan plan) {
    return cursor.isOwnedBy(owner, generation) ? plan.reserve(this) : StatusCode.CONFLICT;
  }

  public void reset() {
    relational.reset();
    lanes.reset();
    key = 0;
    value = 0;
    pendingTextIndex = -1;
    pendingTextLength = 0;
    available = false;
  }

  RelationalScanResult relational() { return relational; }

  StatusCode prepare(int[] descriptors, int columns) {
    return lanes.prepare(descriptors, columns);
  }

  StatusCode set(
      long rowKey,
      long[] projectedValues,
      long projectedNullMask,
      int[] descriptors,
      int columns) {
    if (columns > Long.SIZE) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = beginProjected(rowKey, descriptors, columns);
    for (int index = 0; status.isOk() && index < columns; index++) {
      if ((projectedNullMask >>> index & 1) != 0) setProjectedNull(index);
      else setProjectedValue(index, projectedValues[index]);
    }
    if (!status.isOk()) reset();
    return status;
  }

  StatusCode setWords(
      long rowKey,
      long[] projectedValues,
      SqlNullWords projectedNulls,
      int[] descriptors,
      int columns) {
    if (projectedNulls == null
        || projectedNulls.nullWordCount() != (columns + Long.SIZE - 1) >>> 6) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = beginProjected(rowKey, descriptors, columns);
    for (int index = 0; status.isOk() && index < columns; index++) {
      if (projectedNulls.nullAt(index)) setProjectedNull(index);
      else setProjectedValue(index, projectedValues[index]);
    }
    if (!status.isOk()) reset();
    return status;
  }

  StatusCode setWords(
      long rowKey,
      long[] projectedHighs,
      long[] projectedValues,
      SqlNullWords projectedNulls,
      int[] descriptors,
      int columns) {
    if (projectedHighs == null
        || projectedValues == null
        || projectedHighs.length < columns
        || projectedValues.length < columns
        || projectedNulls == null
        || projectedNulls.nullWordCount() != (columns + Long.SIZE - 1) >>> 6) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = beginProjected(rowKey, descriptors, columns);
    for (int index = 0; status.isOk() && index < columns; index++) {
      if (projectedNulls.nullAt(index)) setProjectedNull(index);
      else if (SqlTypeDescriptor.isWideDecimal(descriptors[index])) {
        setProjectedDecimal128(
            index, projectedHighs[index], projectedValues[index]);
      } else setProjectedValue(index, projectedValues[index]);
    }
    if (!status.isOk()) reset();
    return status;
  }

  StatusCode beginProjected(long rowKey, int[] descriptors, int columns) {
    StatusCode status = lanes.begin(descriptors, columns);
    if (!status.isOk()) return status;
    key = rowKey;
    value = 0;
    pendingTextIndex = -1;
    pendingTextLength = 0;
    available = true;
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

  StatusCode setTextAt(int index, CharSequence source) {
    if (source == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = reservePendingText(index, source.length());
    if (!status.isOk()) return status;
    for (int character = 0; character < source.length(); character++) {
      pendingText[character] = source.charAt(character);
    }
    return lanes.setText(index, pendingText, 0, source.length());
  }

  StatusCode setTextAt(int index, char[] source, int length) {
    return lanes.setText(index, source, 0, length);
  }

  StatusCode setTextAt(int index, char[] source, int offset, int length) {
    return lanes.setText(index, source, offset, length);
  }

  StatusCode beginTextAt(int index, int length) {
    StatusCode status = reservePendingText(index, length);
    if (!status.isOk()) return status;
    pendingTextIndex = index;
    pendingTextLength = length;
    return StatusCode.OK;
  }

  void setTextCharacterAt(int index, int character, char textCharacter) {
    if (index == pendingTextIndex && character >= 0 && character < pendingTextLength) {
      pendingText[character] = textCharacter;
    }
  }

  StatusCode finishTextAt(int index) {
    if (index != pendingTextIndex) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = lanes.setText(index, pendingText, 0, pendingTextLength);
    pendingTextIndex = -1;
    pendingTextLength = 0;
    return status;
  }

  StatusCode setPackedTextAt(int index, long packed) {
    int length = PackedText.length(packed);
    StatusCode reserved = reservePendingText(index, length);
    if (!reserved.isOk()) return reserved;
    for (int character = 0; character < length; character++) {
      pendingText[character] = PackedText.charAt(packed, character);
    }
    StatusCode status = lanes.setText(index, pendingText, 0, length);
    if (status.isOk()) lanes.setValue(index, packed);
    return status;
  }

  StatusCode setUtf8At(
      int index,
      io.riverdb.storage.heap.HeapRowResult source,
      int offset,
      int length) {
    return lanes.setUtf8(index, source, offset, length);
  }

  /** Legacy scalar row-key field; descriptor composite/keyless rows report zero. */
  public long key() { return key; }
  public long value() { return value; }
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
  public int encodedTextLengthAt(int index) { return lanes.encodedTextLength(index); }
  public int copyTextAt(int index, char[] destination, int offset) {
    return lanes.copyText(index, destination, offset);
  }
  public char textCharacterAt(int index, int character) {
    return lanes.textCharacter(index, character);
  }
  public boolean isAvailable() { return available; }

  private StatusCode reservePendingText(int index, int required) {
    if (!available || !lanes.isText(index) || required < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int maximum = Math.min(
        Utf8Text.MAXIMUM_UTF16_CODE_UNITS,
        SqlTypeDescriptor.parameterOne(lanes.descriptor(index)) * 2);
    if (required > maximum) return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    if (required <= pendingText.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        pendingText.length, required, maximum, 8);
    try {
      pendingText = java.util.Arrays.copyOf(pendingText, capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
