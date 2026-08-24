package io.riverdb.base.text;

import java.nio.ByteBuffer;

/** UTF-16 validation and allocation-free UTF-8 encoding. */
final class Utf8TextEncoding {
  private Utf8TextEncoding() {
  }

  static int encodedLength(CharSequence value, int maximumScalars) {
    if (value == null || !validMaximum(maximumScalars)) return -1;
    int bytes = 0;
    int scalars = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return -1;
        scalar = Character.toCodePoint(first, value.charAt(index));
      } else if (Character.isLowSurrogate(first)) return -1;
      else scalar = first;
      if (++scalars > maximumScalars) return -1;
      bytes += encodedBytes(scalar);
    }
    return bytes;
  }

  static int encodedLength(CharSequence value) {
    if (value == null) return -1;
    int bytes = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return -1;
        scalar = Character.toCodePoint(first, value.charAt(index));
      } else if (Character.isLowSurrogate(first)) return -1;
      else scalar = first;
      bytes += encodedBytes(scalar);
    }
    return bytes;
  }

  static int scalarCount(CharSequence value) {
    if (value == null) return -1;
    int scalars = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return -1;
      } else if (Character.isLowSurrogate(first)) return -1;
      scalars++;
    }
    return scalars;
  }

  static int encodedLength(char[] value, int offset, int length, int maximumScalars) {
    if (!validRange(value, offset, length) || !validMaximum(maximumScalars)) return -1;
    int bytes = 0;
    int scalars = 0;
    int end = offset + length;
    for (int index = offset; index < end; index++) {
      char first = value[index];
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= end || !Character.isLowSurrogate(value[index])) return -1;
        scalar = Character.toCodePoint(first, value[index]);
      } else if (Character.isLowSurrogate(first)) return -1;
      else scalar = first;
      if (++scalars > maximumScalars) return -1;
      bytes += encodedBytes(scalar);
    }
    return bytes;
  }

  static int scalarCount(char[] value, int offset, int length) {
    if (!validRange(value, offset, length)) return -1;
    int scalars = 0;
    int end = offset + length;
    for (int index = offset; index < end; index++) {
      char first = value[index];
      if (Character.isHighSurrogate(first)) {
        if (++index >= end || !Character.isLowSurrogate(value[index])) return -1;
      } else if (Character.isLowSurrogate(first)) return -1;
      scalars++;
    }
    return scalars;
  }

  static int encode(CharSequence value, int maximumScalars, ByteBuffer target) {
    int bytes = encodedLength(value, maximumScalars);
    if (bytes < 0 || target == null || target.remaining() < bytes) return -1;
    int start = target.position();
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar = Character.isHighSurrogate(first)
          ? Character.toCodePoint(first, value.charAt(++index)) : first;
      putScalar(target, scalar);
    }
    return target.position() - start;
  }

  static int encode(CharSequence value, ByteBuffer target) {
    int bytes = encodedLength(value);
    if (bytes < 0 || target == null || target.remaining() < bytes) return -1;
    int start = target.position();
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar = Character.isHighSurrogate(first)
          ? Character.toCodePoint(first, value.charAt(++index)) : first;
      putScalar(target, scalar);
    }
    return target.position() - start;
  }

  static int encode(char[] value, int valueOffset, int valueLength, int maximumScalars,
      byte[] target, int targetOffset) {
    if (!validRange(value, valueOffset, valueLength) || target == null || targetOffset < 0
        || !validMaximum(maximumScalars)) return -1;
    int output = targetOffset;
    int scalars = 0;
    int end = valueOffset + valueLength;
    for (int index = valueOffset; index < end; index++) {
      char first = value[index];
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= end || !Character.isLowSurrogate(value[index])) return -1;
        scalar = Character.toCodePoint(first, value[index]);
      } else if (Character.isLowSurrogate(first)) return -1;
      else scalar = first;
      if (++scalars > maximumScalars) return -1;
      int bytes = encodedBytes(scalar);
      if (output > target.length - bytes) return -1;
      output = putScalar(target, output, scalar);
    }
    return output - targetOffset;
  }

  private static boolean validRange(char[] value, int offset, int length) {
    return value != null && offset >= 0 && length >= 0 && offset <= value.length - length;
  }

  private static boolean validMaximum(int maximumScalars) {
    return maximumScalars >= 1 && maximumScalars <= Utf8Text.MAXIMUM_SCALARS;
  }

  private static int encodedBytes(int scalar) {
    if (scalar <= 0x7f) return 1;
    if (scalar <= 0x7ff) return 2;
    return scalar <= 0xffff ? 3 : 4;
  }

  private static void putScalar(ByteBuffer target, int scalar) {
    if (scalar <= 0x7f) target.put((byte) scalar);
    else if (scalar <= 0x7ff) {
      target.put((byte) (0xc0 | scalar >>> 6)); target.put((byte) (0x80 | scalar & 0x3f));
    } else if (scalar <= 0xffff) {
      target.put((byte) (0xe0 | scalar >>> 12)); target.put((byte) (0x80 | scalar >>> 6 & 0x3f));
      target.put((byte) (0x80 | scalar & 0x3f));
    } else {
      target.put((byte) (0xf0 | scalar >>> 18)); target.put((byte) (0x80 | scalar >>> 12 & 0x3f));
      target.put((byte) (0x80 | scalar >>> 6 & 0x3f)); target.put((byte) (0x80 | scalar & 0x3f));
    }
  }

  private static int putScalar(byte[] target, int offset, int scalar) {
    if (scalar <= 0x7f) target[offset++] = (byte) scalar;
    else if (scalar <= 0x7ff) {
      target[offset++] = (byte) (0xc0 | scalar >>> 6); target[offset++] = (byte) (0x80 | scalar & 0x3f);
    } else if (scalar <= 0xffff) {
      target[offset++] = (byte) (0xe0 | scalar >>> 12); target[offset++] = (byte) (0x80 | scalar >>> 6 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar & 0x3f);
    } else {
      target[offset++] = (byte) (0xf0 | scalar >>> 18); target[offset++] = (byte) (0x80 | scalar >>> 12 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar >>> 6 & 0x3f); target[offset++] = (byte) (0x80 | scalar & 0x3f);
    }
    return offset;
  }
}
