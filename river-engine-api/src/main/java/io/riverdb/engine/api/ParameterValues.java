package io.riverdb.engine.api;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Geometrically retained primitive and UTF-8 parameter lanes. */
final class ParameterValues {
  private static final int RETAINED_PARAMETER_FLOOR = 16;
  private static final int RETAINED_TEXT_FLOOR = 4 * 1024;
  private static final int BYTES_PER_PARAMETER = Integer.BYTES * 3 + Long.BYTES * 2 + Byte.BYTES;
  private int[] descriptors = new int[0];
  private long[] values = new long[0];
  private long[] decimalHighs = new long[0];
  private int[] textOffsets = new int[0];
  /* Byte lengths are not a VARCHAR declaration; retain the full value width. */
  private int[] textLengths = new int[0];
  private byte[] nulls = new byte[0];
  private byte[] text = new byte[0];
  private ByteBuffer textView = ByteBuffer.wrap(text);
  private final int parameterLimit;
  private final int textLimit;
  private final RetainedMemoryLease memory;
  private int count;
  private int textBytes;

  ParameterValues(
      int parameterCapacity, int textCapacity, RetainedMemoryLease retainedMemory) {
    if (parameterCapacity < 0 || parameterCapacity > ParameterSet.MAXIMUM_PARAMETERS
        || textCapacity < 0 || textCapacity > ParameterSet.MAXIMUM_TEXT_BYTES) {
      throw new IllegalArgumentException("parameter capacity exceeds the River bound");
    }
    if (retainedMemory == null) throw new IllegalArgumentException("retainedMemory");
    parameterLimit = parameterCapacity;
    textLimit = textCapacity;
    memory = retainedMemory;
  }

  void reset() {
    Arrays.fill(descriptors, 0, count, 0);
    Arrays.fill(values, 0, count, 0);
    Arrays.fill(decimalHighs, 0, count, 0);
    Arrays.fill(textOffsets, 0, count, 0);
    Arrays.fill(textLengths, 0, count, (short) 0);
    Arrays.fill(nulls, 0, count, (byte) 0);
    Arrays.fill(text, 0, textBytes, (byte) 0);
    count = 0;
    textBytes = 0;
  }

  StatusCode appendNull(int descriptor) {
    if (descriptor != 0 && !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = reserve(count + 1, textBytes);
    if (!status.isOk()) return status;
    publish(count++, descriptor, 0, 0, true, 0, 0);
    return StatusCode.OK;
  }

  StatusCode appendFixed(int descriptor, long value) {
    long high = PublicDecimal128.inferredHigh(value, descriptor);
    if (!PublicDecimal128.valid(high, value, descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = reserve(count + 1, textBytes);
    if (!status.isOk()) return status;
    publish(count++, descriptor, high, value, false, 0, 0);
    return StatusCode.OK;
  }

  StatusCode appendDecimal128(int descriptor, long high, long low) {
    if (!PublicDecimal128.isWide(descriptor)
        || !PublicDecimal128.valid(high, low, descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = reserve(count + 1, textBytes);
    if (!status.isOk()) return status;
    publish(count++, descriptor, high, low, false, 0, 0);
    return StatusCode.OK;
  }

  StatusCode appendText(int descriptor, CharSequence value) {
    int maximumScalars = varcharScalars(descriptor);
    if (maximumScalars < 0 || value == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    int scalars = Utf8Text.scalarCount(value);
    if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (scalars > maximumScalars) return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    int bytes = Utf8Text.encodedLength(value, maximumScalars);
    StatusCode status = reserve(count + 1, textBytes + bytes);
    if (!status.isOk()) return status;
    textView.clear();
    textView.position(textBytes);
    textView.limit(textBytes + bytes);
    if (Utf8Text.encode(value, maximumScalars, textView) != bytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    publish(count++, descriptor, 0, 0, false, textBytes, bytes);
    textBytes += bytes;
    return StatusCode.OK;
  }

  StatusCode appendUtf8(int descriptor, ByteBuffer source, int offset, int length) {
    int maximumScalars = varcharScalars(descriptor);
    if (maximumScalars < 0 || source == null || offset < 0 || length < 0
        || offset > source.limit() - length) return StatusCode.INVALID_EXTERNAL_INPUT;
    int scalars = Utf8Text.validate(source, offset, length);
    if (scalars < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (scalars > maximumScalars) return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    StatusCode status = reserve(count + 1, textBytes + length);
    if (!status.isOk()) return status;
    int target = textBytes;
    for (int input = offset; input < offset + length; input++) text[target++] = source.get(input);
    publish(count++, descriptor, 0, 0, false, textBytes, length);
    textBytes += length;
    return StatusCode.OK;
  }

  StatusCode reserve(int parameters, int requestedTextBytes) {
    if (parameters < 0 || requestedTextBytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (parameters > parameterLimit || requestedTextBytes > textLimit) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (parameters <= descriptors.length && requestedTextBytes <= text.length) {
      return StatusCode.OK;
    }
    int laneCapacity = parameters <= descriptors.length ? descriptors.length
        : BoundedArrayGrowth.capacity(
            descriptors.length, parameters, parameterLimit, Math.min(8, parameterLimit));
    int textCapacity = requestedTextBytes <= text.length ? text.length
        : BoundedArrayGrowth.capacity(
            text.length, requestedTextBytes, textLimit, Math.min(8, textLimit));
    long retained = retainedBytes(laneCapacity, textCapacity);
    StatusCode admitted = memory.resize(retained);
    if (!admitted.isOk()) return admitted;
    try {
      int[] newDescriptors = Arrays.copyOf(descriptors, laneCapacity);
      long[] newValues = Arrays.copyOf(values, laneCapacity);
      long[] newDecimalHighs = Arrays.copyOf(decimalHighs, laneCapacity);
      int[] newOffsets = Arrays.copyOf(textOffsets, laneCapacity);
      int[] newLengths = Arrays.copyOf(textLengths, laneCapacity);
      byte[] newNulls = Arrays.copyOf(nulls, laneCapacity);
      byte[] newText = Arrays.copyOf(text, textCapacity);
      ByteBuffer newView = ByteBuffer.wrap(newText);
      descriptors = newDescriptors;
      values = newValues;
      decimalHighs = newDecimalHighs;
      textOffsets = newOffsets;
      textLengths = newLengths;
      nulls = newNulls;
      text = newText;
      textView = newView;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedBytes(descriptors.length, text.length));
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode releaseHighWater() {
    reset();
    int lanes = Math.min(descriptors.length, RETAINED_PARAMETER_FLOOR);
    int textCapacity = Math.min(text.length, RETAINED_TEXT_FLOOR);
    if (lanes == descriptors.length && textCapacity == text.length) return StatusCode.OK;
    try {
      int[] newDescriptors = new int[lanes];
      long[] newValues = new long[lanes];
      long[] newDecimalHighs = new long[lanes];
      int[] newOffsets = new int[lanes];
      int[] newLengths = new int[lanes];
      byte[] newNulls = new byte[lanes];
      byte[] newText = new byte[textCapacity];
      ByteBuffer newView = ByteBuffer.wrap(newText);
      descriptors = newDescriptors;
      values = newValues;
      decimalHighs = newDecimalHighs;
      textOffsets = newOffsets;
      textLengths = newLengths;
      nulls = newNulls;
      text = newText;
      textView = newView;
      return memory.resize(retainedBytes(lanes, textCapacity));
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode release() {
    reset();
    descriptors = new int[0];
    values = new long[0];
    decimalHighs = new long[0];
    textOffsets = new int[0];
    textLengths = new int[0];
    nulls = new byte[0];
    text = new byte[0];
    textView = ByteBuffer.wrap(text);
    return memory.resize(0);
  }

  long retainedBytes() { return memory.retainedBytes(); }
  static long maximumRetainedBytes() {
    return retainedBytes(ParameterSet.MAXIMUM_PARAMETERS, ParameterSet.MAXIMUM_TEXT_BYTES);
  }
  static long retainedFloorBytes() {
    return retainedBytes(RETAINED_PARAMETER_FLOOR, RETAINED_TEXT_FLOOR);
  }

  int count() { return count; }
  int textBytes() { return textBytes; }
  boolean isNull(int index) { return valid(index) && nulls[index] != 0; }
  int descriptor(int index) { return valid(index) ? descriptors[index] : 0; }
  long value(int index) { return valid(index) ? values[index] : 0; }
  long decimalHigh(int index) { return valid(index) ? decimalHighs[index] : 0; }
  int textLength(int index) {
    return valid(index) ? textLengths[index] : -1;
  }
  byte textByte(int index, int byteIndex) {
    int length = textLength(index);
    return byteIndex >= 0 && byteIndex < length ? text[textOffsets[index] + byteIndex] : 0;
  }
  int copyText(int index, char[] target, int offset) {
    int length = textLength(index);
    if (length < 0 || target == null || offset < 0) return -1;
    textView.clear();
    return Utf8Text.decode(textView, textOffsets[index], length, target, offset);
  }

  private void publish(int index, int descriptor, long decimalHigh, long value, boolean nullValue,
      int textOffset, int textLength) {
    descriptors[index] = descriptor;
    decimalHighs[index] = decimalHigh;
    values[index] = value;
    nulls[index] = nullValue ? (byte) 1 : 0;
    textOffsets[index] = textOffset;
    textLengths[index] = textLength;
  }

  private boolean valid(int index) { return index >= 0 && index < count; }
  private static long retainedBytes(int parameters, int textBytes) {
    return (long) parameters * BYTES_PER_PARAMETER + textBytes;
  }
  private static int varcharScalars(int descriptor) {
    return SqlTypeDescriptor.isValid(descriptor)
            && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? SqlTypeDescriptor.parameterOne(descriptor) : -1;
  }
}
