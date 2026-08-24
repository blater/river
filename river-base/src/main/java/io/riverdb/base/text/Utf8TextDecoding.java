package io.riverdb.base.text;

import java.nio.ByteBuffer;

/** Canonical UTF-8 validation and decoding into caller-owned storage. */
final class Utf8TextDecoding {
  private Utf8TextDecoding() {
  }

  static int validate(ByteBuffer source, int offset, int length, int maximumScalars) {
    if (!validRange(source, offset, length) || maximumScalars < 1
        || maximumScalars > Utf8Text.MAXIMUM_SCALARS) return -1;
    int scalars = validate(source, offset, length);
    return scalars >= 0 && scalars <= maximumScalars ? scalars : -1;
  }

  static int validate(ByteBuffer source, int offset, int length) {
    if (!validRange(source, offset, length)) return -1;
    int end = offset + length;
    int scalars = 0;
    int index = offset;
    while (index < end) {
      int decoded = decodeScalar(source, index, end);
      if (decoded < 0) return -1;
      scalars++;
      index += decoded >>> 24;
    }
    return scalars;
  }

  static int decode(ByteBuffer source, int offset, int length, char[] target, int targetOffset) {
    if (!validRange(source, offset, length) || target == null || targetOffset < 0) return -1;
    int end = offset + length;
    int output = targetOffset;
    int index = offset;
    while (index < end) {
      int decoded = decodeScalar(source, index, end);
      if (decoded < 0) return -1;
      int scalar = decoded & 0x00ff_ffff;
      int chars = scalar >= Character.MIN_SUPPLEMENTARY_CODE_POINT ? 2 : 1;
      if (output > target.length - chars) return -1;
      if (chars == 1) target[output++] = (char) scalar;
      else {
        target[output++] = Character.highSurrogate(scalar);
        target[output++] = Character.lowSurrogate(scalar);
      }
      index += decoded >>> 24;
    }
    return output - targetOffset;
  }

  private static boolean validRange(ByteBuffer source, int offset, int length) {
    return source != null && offset >= 0 && length >= 0 && offset <= source.limit() - length;
  }

  private static int decodeScalar(ByteBuffer source, int offset, int end) {
    int first = Byte.toUnsignedInt(source.get(offset));
    if (first <= 0x7f) return 1 << 24 | first;
    int bytes;
    int scalar;
    int minimum;
    if (first >= 0xc2 && first <= 0xdf) { bytes = 2; scalar = first & 0x1f; minimum = 0x80; }
    else if (first >= 0xe0 && first <= 0xef) { bytes = 3; scalar = first & 0x0f; minimum = 0x800; }
    else if (first >= 0xf0 && first <= 0xf4) { bytes = 4; scalar = first & 0x07; minimum = 0x10000; }
    else return -1;
    if (offset > end - bytes) return -1;
    for (int index = 1; index < bytes; index++) {
      int continuation = Byte.toUnsignedInt(source.get(offset + index));
      if ((continuation & 0xc0) != 0x80) return -1;
      scalar = scalar << 6 | continuation & 0x3f;
    }
    if (scalar < minimum || scalar > Character.MAX_CODE_POINT
        || scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE) return -1;
    return bytes << 24 | scalar;
  }
}
