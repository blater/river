package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Caller-owned bounded typed values borrowed for one synchronous SQL admission. */
public final class ParameterSet {
  public static final int MAXIMUM_PARAMETERS = 512;
  public static final int MAXIMUM_TEXT_BYTES = 16 * 1024;

  private final int[] descriptors;
  private final long[] values;
  private final int[] textOffsets;
  private final short[] textLengths;
  private final byte[] nulls;
  private final byte[] text;
  private final ByteBuffer textView;
  private int count;
  private int textBytes;

  public ParameterSet(int parameterCapacity, int textCapacity) {
    if (parameterCapacity < 0 || parameterCapacity > MAXIMUM_PARAMETERS
        || textCapacity < 0 || textCapacity > MAXIMUM_TEXT_BYTES) {
      throw new IllegalArgumentException("parameter capacity exceeds the River bound");
    }
    descriptors = new int[parameterCapacity];
    values = new long[parameterCapacity];
    textOffsets = new int[parameterCapacity];
    textLengths = new short[parameterCapacity];
    nulls = new byte[parameterCapacity];
    text = new byte[textCapacity];
    textView = ByteBuffer.wrap(text);
  }

  public void reset() {
    Arrays.fill(descriptors, 0, count, 0);
    Arrays.fill(values, 0, count, 0);
    Arrays.fill(textOffsets, 0, count, 0);
    Arrays.fill(textLengths, 0, count, (short) 0);
    Arrays.fill(nulls, 0, count, (byte) 0);
    Arrays.fill(text, 0, textBytes, (byte) 0);
    count = 0;
    textBytes = 0;
  }

  public StatusCode appendNull(int descriptor) {
    if (descriptor != 0 && !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = reserve();
    if (index < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    publish(index, descriptor, 0, true, 0, 0);
    return StatusCode.OK;
  }

  public StatusCode appendFixed(int descriptor, long value) {
    if (!SqlValueDomain.validFixed(descriptor, value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = reserve();
    if (index < 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    publish(index, descriptor, value, false, 0, 0);
    return StatusCode.OK;
  }

  public StatusCode appendText(int descriptor, CharSequence value) {
    int maximumScalars = varcharScalars(descriptor);
    if (maximumScalars < 0 || value == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int scalars = Utf8Text.scalarCount(value);
    if (scalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (scalars > maximumScalars) {
      return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    }
    int bytes = Utf8Text.encodedLength(value, maximumScalars);
    if (count >= descriptors.length || bytes > text.length - textBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    textView.clear();
    textView.position(textBytes);
    textView.limit(textBytes + bytes);
    if (Utf8Text.encode(value, maximumScalars, textView) != bytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int index = count++;
    publish(index, descriptor, 0, false, textBytes, bytes);
    textBytes += bytes;
    return StatusCode.OK;
  }

  public StatusCode appendUtf8(
      int descriptor, ByteBuffer source, int offset, int length) {
    int maximumScalars = varcharScalars(descriptor);
    if (maximumScalars < 0 || source == null || offset < 0 || length < 0
        || offset > source.limit() - length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int scalars = Utf8Text.validate(source, offset, length);
    if (scalars < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (scalars > maximumScalars) {
      return StatusCode.STRING_DATA_RIGHT_TRUNCATION;
    }
    if (count >= descriptors.length || length > text.length - textBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int index = count++;
    int target = textBytes;
    for (int input = offset; input < offset + length; input++) {
      text[target++] = source.get(input);
    }
    publish(index, descriptor, 0, false, textBytes, length);
    textBytes += length;
    return StatusCode.OK;
  }

  public int count() {
    return count;
  }

  public boolean isNull(int index) {
    return index >= 0 && index < count && nulls[index] != 0;
  }

  public int typeDescriptorAt(int index) {
    return index >= 0 && index < count ? descriptors[index] : 0;
  }

  public long valueAt(int index) {
    return index >= 0 && index < count ? values[index] : 0;
  }

  public int textLengthAt(int index) {
    return index >= 0 && index < count
        ? Short.toUnsignedInt(textLengths[index]) : -1;
  }

  public byte textByteAt(int index, int byteIndex) {
    int length = textLengthAt(index);
    return byteIndex >= 0 && byteIndex < length
        ? text[textOffsets[index] + byteIndex] : 0;
  }

  public int copyTextAt(int index, char[] target, int offset) {
    int length = textLengthAt(index);
    if (length < 0 || target == null || offset < 0) {
      return -1;
    }
    textView.clear();
    return Utf8Text.decode(
        textView, textOffsets[index], length, target, offset);
  }

  public int textBytes() {
    return textBytes;
  }

  private int reserve() {
    return count < descriptors.length ? count++ : -1;
  }

  private void publish(
      int index,
      int descriptor,
      long value,
      boolean nullValue,
      int textOffset,
      int textLength) {
    descriptors[index] = descriptor;
    values[index] = value;
    nulls[index] = nullValue ? (byte) 1 : 0;
    textOffsets[index] = textOffset;
    textLengths[index] = (short) textLength;
  }

  private static int varcharScalars(int descriptor) {
    return SqlTypeDescriptor.isValid(descriptor)
            && SqlTypeDescriptor.typeId(descriptor)
                == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? SqlTypeDescriptor.parameterOne(descriptor) : -1;
  }
}
