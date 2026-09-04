package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Reusable actual-count values, descriptors, null words, and packed UTF-8 text. */
final class SqlResultLanes {
  private final ColumnBitSet nulls = new ColumnBitSet();
  private final SqlResultTextLanes text = new SqlResultTextLanes();
  private long[] highValues = new long[0];
  private long[] values = new long[0];
  private int[] descriptors = new int[0];
  private int count;

  StatusCode begin(int[] sourceDescriptors, int columns) {
    if (sourceDescriptors == null || columns < 0 || columns > sourceDescriptors.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (columns > SqlShapeLimits.MAX_RESULT_COLUMNS) return StatusCode.RESOURCE_EXHAUSTED;
    int textBytes = maximumTextBytes(sourceDescriptors, columns);
    if (textBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = reserve(columns, textBytes);
    if (status.isOk()) status = text.reserve(
        columns, textBytes, maximumTextCharacters(sourceDescriptors, columns));
    if (!status.isOk()) return status;
    clear();
    status = nulls.clearForSize(columns);
    if (!status.isOk()) return status;
    System.arraycopy(sourceDescriptors, 0, descriptors, 0, columns);
    count = columns;
    return StatusCode.OK;
  }

  StatusCode prepare(int[] sourceDescriptors, int columns) {
    if (sourceDescriptors == null || columns < 0 || columns > sourceDescriptors.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (columns > SqlShapeLimits.MAX_RESULT_COLUMNS) return StatusCode.RESOURCE_EXHAUSTED;
    int textBytes = maximumTextBytes(sourceDescriptors, columns);
    if (textBytes < 0) return StatusCode.RESOURCE_EXHAUSTED;
    StatusCode status = reserve(columns, textBytes);
    return status.isOk() ? text.reserve(
        columns, textBytes, maximumTextCharacters(sourceDescriptors, columns)) : status;
  }

  StatusCode beginSingle(int descriptor) {
    if (!SqlTypeDescriptor.isValid(descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    int textBytes = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? SqlTypeDescriptor.parameterOne(descriptor) * 4 : 0;
    StatusCode status = reserve(1, textBytes);
    if (status.isOk()) status = text.reserve(1, textBytes, textBytes / 2);
    if (!status.isOk()) return status;
    clear();
    status = nulls.clearForSize(1);
    if (status.isOk()) {
      descriptors[0] = descriptor;
      count = 1;
    }
    return status;
  }

  StatusCode reserve(int columns, int textBytes) {
    if (columns < 0 || columns > SqlShapeLimits.MAX_RESULT_COLUMNS
        || textBytes < 0 || textBytes > SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = reserveArrays(columns);
    if (!status.isOk()) return status;
    status = nulls.reserve(columns, SqlShapeLimits.MAX_RESULT_COLUMNS);
    return status.isOk() ? text.reserve(columns, textBytes, 0) : status;
  }


  void reset() {
    clear();
    nulls.reset();
    count = 0;
  }

  void setValue(int index, long value) {
    setValue(index, value >> 63, value);
  }

  void setValue(int index, long high, long value) {
    highValues[index] = high;
    values[index] = value;
    nulls.clear(index);
  }

  void setNull(int index) {
    highValues[index] = 0;
    values[index] = 0;
    text.clearLane(index);
    nulls.set(index);
  }

  StatusCode setText(int index, char[] source, int offset, int length) {
    if (!isText(index) || source == null || offset < 0 || length < 0
        || offset > source.length - length) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = text.set(index, descriptors[index], source, offset, length);
    if (status.isOk()) publishText(index);
    return status;
  }

  StatusCode setUtf8(int index, HeapRowResult source, int offset, int length) {
    if (!isText(index) || source == null || offset < 0 || length < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.setUtf8(index, descriptors[index], source, offset, length);
    if (status.isOk()) publishText(index);
    return status;
  }

  StatusCode setUtf8(int index, ByteBuffer source, int offset, int length) {
    if (!isText(index) || source == null || offset < 0 || length < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.setUtf8(index, descriptors[index], source, offset, length);
    if (status.isOk()) publishText(index);
    return status;
  }

  int count() { return count; }
  long value(int index) { return valid(index) ? values[index] : 0; }
  long highValue(int index) { return valid(index) ? highValues[index] : 0; }
  int descriptor(int index) { return valid(index) ? descriptors[index] : 0; }
  boolean isNull(int index) { return valid(index) && nulls.get(index); }
  long nullWord(int word) { return nulls.word(word); }
  int nullWordCount() { return nulls.wordCount(); }
  boolean isText(int index) {
    return valid(index)
        && SqlTypeDescriptor.typeId(descriptors[index]) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  int textLength(int index) {
    return isText(index) && !isNull(index)
        ? text.length(index) : -1;
  }

  int encodedTextLength(int index) {
    return isText(index) && !isNull(index) ? text.byteLength(index) : -1;
  }

  int copyText(int index, char[] destination, int offset) {
    if (!isText(index) || isNull(index)) return -1;
    return text.copy(index, destination, offset);
  }

  char textCharacter(int index, int character) {
    return text.character(index, character);
  }

  private StatusCode reserveArrays(int columns) {
    if (columns <= values.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        values.length, columns, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    try {
      long[] nextValues = new long[capacity];
      long[] nextHighValues = new long[capacity];
      int[] nextDescriptors = new int[capacity];
      System.arraycopy(values, 0, nextValues, 0, count);
      System.arraycopy(highValues, 0, nextHighValues, 0, count);
      System.arraycopy(descriptors, 0, nextDescriptors, 0, count);
      values = nextValues;
      highValues = nextHighValues;
      descriptors = nextDescriptors;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private void clear() {
    for (int index = 0; index < count; index++) {
      values[index] = 0;
      highValues[index] = 0;
      descriptors[index] = 0;
    }
    text.clear(count);
  }

  private void publishText(int index) {
    values[index] = 0;
    highValues[index] = 0;
    nulls.clear(index);
  }

  private boolean valid(int index) { return index >= 0 && index < count; }

  private static int maximumTextBytes(int[] descriptors, int count) {
    long bytes = 0;
    for (int index = 0; index < count; index++) {
      int descriptor = descriptors[index];
      if (!SqlTypeDescriptor.isValid(descriptor)) return -1;
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += (long) SqlTypeDescriptor.parameterOne(descriptor) * 4;
        if (bytes > SqlShapeLimits.MAX_ENCODED_RESULT_ROW_BYTES) return -1;
      }
    }
    return (int) bytes;
  }

  private static int maximumTextCharacters(int[] descriptors, int count) {
    int maximum = 0;
    for (int index = 0; index < count; index++) {
      if (SqlTypeDescriptor.typeId(descriptors[index]) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        maximum = Math.max(maximum, SqlTypeDescriptor.parameterOne(descriptors[index]) * 2);
      }
    }
    return maximum;
  }
}
