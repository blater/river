package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import java.nio.ByteBuffer;

/** Decodes response column descriptors, metadata, and values. */
final class ProtocolResponseValueDecoder {
  private ProtocolResponseValueDecoder() { }

  static int types(ByteBuffer bytes, int offset, int end, int columns, ProtocolResponse result) {
    if (offset > end - columns * Integer.BYTES) return -1;
    for (int index = 0; index < columns; index++) {
      int descriptor = bytes.getInt(offset);
      if (!SqlTypeDescriptor.isValid(descriptor)) return -1;
      result.typeDescriptorAt(index, descriptor);
      offset += Integer.BYTES;
    }
    return offset;
  }

  static StatusCode metadata(ByteBuffer bytes, int offset, int end, int columns,
      ProtocolResponse result) {
    for (int index = 0; index < columns; index++) {
      if (offset >= end) return StatusCode.INVALID_EXTERNAL_INPUT;
      int length = bytes.get(offset++) & 0xff;
      if (!validColumnName(bytes, offset, length, end)) return StatusCode.INVALID_EXTERNAL_INPUT;
      result.columnNameAt(index, bytes, offset, length);
      offset += length;
    }
    return offset == end ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  static StatusCode values(ByteBuffer bytes, int offset, int end, int columns, long nullMask,
      ProtocolResponse result) {
    for (int index = 0; index < columns; index++) {
      int next = result.isVarchar(index)
          ? text(bytes, offset, end, index, nullMask, result)
          : fixed(bytes, offset, end, index, nullMask, result);
      if (next < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      offset = next;
    }
    return offset == end ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static int text(ByteBuffer bytes, int offset, int end, int index, long nullMask,
      ProtocolResponse result) {
    if (offset > end - Short.BYTES) return -1;
    int length = Short.toUnsignedInt(bytes.getShort(offset));
    offset += Short.BYTES;
    int maximumScalars = SqlTypeDescriptor.parameterOne(result.typeDescriptorAt(index));
    if (length > Utf8Text.MAXIMUM_BYTES || (nullMask & 1L << index) != 0 && length != 0
        || offset > end - length || Utf8Text.validate(bytes, offset, length, maximumScalars) < 0
        || !result.textAt(index, bytes, offset, length)) return -1;
    return offset + length;
  }

  private static int fixed(ByteBuffer bytes, int offset, int end, int index, long nullMask,
      ProtocolResponse result) {
    if (offset > end - Long.BYTES) return -1;
    long value = bytes.getLong(offset);
    if ((nullMask & 1L << index) == 0
        && !SqlValueDomain.validFixed(result.typeDescriptorAt(index), value)) return -1;
    result.valueAt(index, value);
    return offset + Long.BYTES;
  }

  private static boolean validColumnName(ByteBuffer source, int offset, int length, int end) {
    if (length <= 0 || length > ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES
        || offset > end - length || !identifierStart(source.get(offset))) return false;
    for (int index = 1; index < length; index++) {
      if (!identifierPart(source.get(offset + index))) return false;
    }
    return true;
  }

  private static boolean identifierStart(byte value) {
    return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value == '_';
  }

  private static boolean identifierPart(byte value) {
    return identifierStart(value) || value >= '0' && value <= '9';
  }
}
