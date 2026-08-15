package io.riverdb.base.text;

import java.nio.ByteBuffer;

/** Strict, allocation-free UTF-8 operations over caller-owned storage. */
public final class Utf8Text {
  public static final int MAXIMUM_SCALARS = 255;
  public static final int MAXIMUM_BYTES = MAXIMUM_SCALARS * 4;

  private Utf8Text() {
  }

  /** Returns the encoded byte count, or {@code -1} for invalid UTF-16. */
  public static int encodedLength(CharSequence value) {
    if (value == null) {
      return -1;
    }
    int bytes = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length()
            || !Character.isLowSurrogate(value.charAt(index))) {
          return -1;
        }
        scalar = Character.toCodePoint(first, value.charAt(index));
      } else if (Character.isLowSurrogate(first)) {
        return -1;
      } else {
        scalar = first;
      }
      bytes += encodedBytes(scalar);
    }
    return bytes;
  }

  /** Returns the encoded byte count, or {@code -1} for invalid UTF-16 or excess scalars. */
  public static int encodedLength(CharSequence value, int maximumScalars) {
    if (value == null || !validMaximum(maximumScalars)) {
      return -1;
    }
    int bytes = 0;
    int scalars = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length()) {
          return -1;
        }
        char second = value.charAt(index);
        if (!Character.isLowSurrogate(second)) {
          return -1;
        }
        scalar = Character.toCodePoint(first, second);
      } else if (Character.isLowSurrogate(first)) {
        return -1;
      } else {
        scalar = first;
      }
      if (++scalars > maximumScalars) {
        return -1;
      }
      bytes += encodedBytes(scalar);
    }
    return bytes;
  }

  /** Returns the Unicode scalar count, or {@code -1} for invalid UTF-16. */
  public static int scalarCount(CharSequence value) {
    if (value == null) {
      return -1;
    }
    int scalars = 0;
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      if (Character.isHighSurrogate(first)) {
        if (++index >= value.length()
            || !Character.isLowSurrogate(value.charAt(index))) {
          return -1;
        }
      } else if (Character.isLowSurrogate(first)) {
        return -1;
      }
      scalars++;
    }
    return scalars;
  }

  /** Returns the encoded byte count for a caller-owned UTF-16 range. */
  public static int encodedLength(
      char[] value,
      int offset,
      int length,
      int maximumScalars) {
    if (value == null
        || offset < 0
        || length < 0
        || offset > value.length - length
        || !validMaximum(maximumScalars)) {
      return -1;
    }
    int bytes = 0;
    int scalars = 0;
    int end = offset + length;
    for (int index = offset; index < end; index++) {
      char first = value[index];
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= end || !Character.isLowSurrogate(value[index])) {
          return -1;
        }
        scalar = Character.toCodePoint(first, value[index]);
      } else if (Character.isLowSurrogate(first)) {
        return -1;
      } else {
        scalar = first;
      }
      if (++scalars > maximumScalars) {
        return -1;
      }
      bytes += encodedBytes(scalar);
    }
    return bytes;
  }

  /** Returns the Unicode scalar count, or {@code -1} for invalid UTF-16 or bounds. */
  public static int scalarCount(char[] value, int offset, int length) {
    if (value == null
        || offset < 0
        || length < 0
        || offset > value.length - length) {
      return -1;
    }
    int scalars = 0;
    int end = offset + length;
    for (int index = offset; index < end; index++) {
      char first = value[index];
      if (Character.isHighSurrogate(first)) {
        if (++index >= end || !Character.isLowSurrogate(value[index])) {
          return -1;
        }
      } else if (Character.isLowSurrogate(first)) {
        return -1;
      }
      scalars++;
    }
    return scalars;
  }

  /** Encodes at the target's current position and advances it on success. */
  public static int encode(
      CharSequence value,
      int maximumScalars,
      ByteBuffer target) {
    int bytes = encodedLength(value, maximumScalars);
    if (bytes < 0 || target == null || target.remaining() < bytes) {
      return -1;
    }
    int start = target.position();
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar;
      if (Character.isHighSurrogate(first)) {
        scalar = Character.toCodePoint(first, value.charAt(++index));
      } else {
        scalar = first;
      }
      putScalar(target, scalar);
    }
    return target.position() - start;
  }

  /** Encodes all valid UTF-16 at the target's current position. */
  public static int encode(CharSequence value, ByteBuffer target) {
    int bytes = encodedLength(value);
    if (bytes < 0 || target == null || target.remaining() < bytes) {
      return -1;
    }
    int start = target.position();
    for (int index = 0; index < value.length(); index++) {
      char first = value.charAt(index);
      int scalar = Character.isHighSurrogate(first)
          ? Character.toCodePoint(first, value.charAt(++index)) : first;
      putScalar(target, scalar);
    }
    return target.position() - start;
  }

  /** Encodes a caller-owned UTF-16 range directly into a caller-owned byte array. */
  public static int encode(
      char[] value,
      int valueOffset,
      int valueLength,
      int maximumScalars,
      byte[] target,
      int targetOffset) {
    if (value == null
        || valueOffset < 0
        || valueLength < 0
        || valueOffset > value.length - valueLength
        || target == null
        || targetOffset < 0
        || !validMaximum(maximumScalars)) {
      return -1;
    }
    int output = targetOffset;
    int scalars = 0;
    int end = valueOffset + valueLength;
    for (int index = valueOffset; index < end; index++) {
      char first = value[index];
      int scalar;
      if (Character.isHighSurrogate(first)) {
        if (++index >= end || !Character.isLowSurrogate(value[index])) {
          return -1;
        }
        scalar = Character.toCodePoint(first, value[index]);
      } else if (Character.isLowSurrogate(first)) {
        return -1;
      } else {
        scalar = first;
      }
      if (++scalars > maximumScalars) {
        return -1;
      }
      int bytes = encodedBytes(scalar);
      if (output > target.length - bytes) {
        return -1;
      }
      output = putScalar(target, output, scalar);
    }
    return output - targetOffset;
  }

  /** Returns the Unicode scalar count, or {@code -1} for non-canonical UTF-8. */
  public static int validate(
      ByteBuffer source,
      int offset,
      int length,
      int maximumScalars) {
    if (source == null
        || offset < 0
        || length < 0
        || offset > source.limit() - length
        || !validMaximum(maximumScalars)) {
      return -1;
    }
    int scalars = validate(source, offset, length);
    return scalars >= 0 && scalars <= maximumScalars ? scalars : -1;
  }

  /** Returns the Unicode scalar count, or {@code -1} for non-canonical UTF-8. */
  public static int validate(ByteBuffer source, int offset, int length) {
    if (source == null
        || offset < 0
        || length < 0
        || offset > source.limit() - length) {
      return -1;
    }
    int end = offset + length;
    int scalars = 0;
    int index = offset;
    while (index < end) {
      int decoded = decodeScalar(source, index, end);
      if (decoded < 0) {
        return -1;
      }
      scalars++;
      index += decoded >>> 24;
    }
    return scalars;
  }

  /** Copies decoded UTF-16 to caller storage, returning chars written or {@code -1}. */
  public static int decode(
      ByteBuffer source,
      int offset,
      int length,
      char[] target,
      int targetOffset) {
    if (source == null
        || offset < 0
        || length < 0
        || offset > source.limit() - length
        || target == null
        || targetOffset < 0) {
      return -1;
    }
    int end = offset + length;
    int output = targetOffset;
    int index = offset;
    while (index < end) {
      int decoded = decodeScalar(source, index, end);
      if (decoded < 0) {
        return -1;
      }
      int scalar = decoded & 0x00ff_ffff;
      int chars = scalar >= Character.MIN_SUPPLEMENTARY_CODE_POINT ? 2 : 1;
      if (output > target.length - chars) {
        return -1;
      }
      if (chars == 1) {
        target[output++] = (char) scalar;
      } else {
        target[output++] = Character.highSurrogate(scalar);
        target[output++] = Character.lowSurrogate(scalar);
      }
      index += decoded >>> 24;
    }
    return output - targetOffset;
  }

  /** Unicode-code-point order; canonical UTF-8 preserves this under unsigned byte order. */
  public static int compare(
      ByteBuffer left,
      int leftOffset,
      int leftLength,
      ByteBuffer right,
      int rightOffset,
      int rightLength) {
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(left.get(leftOffset + index)),
          Byte.toUnsignedInt(right.get(rightOffset + index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftLength, rightLength);
  }

  private static boolean validMaximum(int maximumScalars) {
    return maximumScalars >= 1 && maximumScalars <= MAXIMUM_SCALARS;
  }

  private static int encodedBytes(int scalar) {
    if (scalar <= 0x7f) {
      return 1;
    }
    if (scalar <= 0x7ff) {
      return 2;
    }
    return scalar <= 0xffff ? 3 : 4;
  }

  private static void putScalar(ByteBuffer target, int scalar) {
    if (scalar <= 0x7f) {
      target.put((byte) scalar);
    } else if (scalar <= 0x7ff) {
      target.put((byte) (0xc0 | scalar >>> 6));
      target.put((byte) (0x80 | scalar & 0x3f));
    } else if (scalar <= 0xffff) {
      target.put((byte) (0xe0 | scalar >>> 12));
      target.put((byte) (0x80 | scalar >>> 6 & 0x3f));
      target.put((byte) (0x80 | scalar & 0x3f));
    } else {
      target.put((byte) (0xf0 | scalar >>> 18));
      target.put((byte) (0x80 | scalar >>> 12 & 0x3f));
      target.put((byte) (0x80 | scalar >>> 6 & 0x3f));
      target.put((byte) (0x80 | scalar & 0x3f));
    }
  }

  private static int putScalar(byte[] target, int offset, int scalar) {
    if (scalar <= 0x7f) {
      target[offset++] = (byte) scalar;
    } else if (scalar <= 0x7ff) {
      target[offset++] = (byte) (0xc0 | scalar >>> 6);
      target[offset++] = (byte) (0x80 | scalar & 0x3f);
    } else if (scalar <= 0xffff) {
      target[offset++] = (byte) (0xe0 | scalar >>> 12);
      target[offset++] = (byte) (0x80 | scalar >>> 6 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar & 0x3f);
    } else {
      target[offset++] = (byte) (0xf0 | scalar >>> 18);
      target[offset++] = (byte) (0x80 | scalar >>> 12 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar >>> 6 & 0x3f);
      target[offset++] = (byte) (0x80 | scalar & 0x3f);
    }
    return offset;
  }

  private static int decodeScalar(ByteBuffer source, int offset, int end) {
    int first = Byte.toUnsignedInt(source.get(offset));
    if (first <= 0x7f) {
      return 1 << 24 | first;
    }
    int bytes;
    int scalar;
    int minimum;
    if (first >= 0xc2 && first <= 0xdf) {
      bytes = 2;
      scalar = first & 0x1f;
      minimum = 0x80;
    } else if (first >= 0xe0 && first <= 0xef) {
      bytes = 3;
      scalar = first & 0x0f;
      minimum = 0x800;
    } else if (first >= 0xf0 && first <= 0xf4) {
      bytes = 4;
      scalar = first & 0x07;
      minimum = 0x10000;
    } else {
      return -1;
    }
    if (offset > end - bytes) {
      return -1;
    }
    for (int index = 1; index < bytes; index++) {
      int continuation = Byte.toUnsignedInt(source.get(offset + index));
      if ((continuation & 0xc0) != 0x80) {
        return -1;
      }
      scalar = scalar << 6 | continuation & 0x3f;
    }
    if (scalar < minimum
        || scalar > Character.MAX_CODE_POINT
        || scalar >= Character.MIN_SURROGATE
            && scalar <= Character.MAX_SURROGATE) {
      return -1;
    }
    return bytes << 24 | scalar;
  }
}
