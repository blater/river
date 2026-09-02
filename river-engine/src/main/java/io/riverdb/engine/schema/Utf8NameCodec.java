package io.riverdb.engine.schema;

import io.riverdb.base.text.Utf8Text;

/** Strict UTF-8 name primitives used while admitting immutable descriptors. */
final class Utf8NameCodec {
  private Utf8NameCodec() {
  }

  static int length(CharSequence value) {
    if (value == null || value.length() == 0) return -1;
    int scalars = Utf8Text.scalarCount(value);
    return scalars < 1 || scalars > ColumnDescriptorSet.MAXIMUM_NAME_SCALARS
        ? -1 : Utf8Text.encodedLength(value, ColumnDescriptorSet.MAXIMUM_NAME_SCALARS);
  }

  static int encode(CharSequence value, byte[] target, int offset) {
    int start = offset;
    for (int index = 0; index < value.length(); index++) {
      int codePoint = codePointAt(value, index);
      if (Character.isHighSurrogate(value.charAt(index))) index++;
      int width = width(codePoint);
      target[offset++] = (byte) firstByte(codePoint, width);
      for (int shift = (width - 2) * 6; shift >= 0; shift -= 6) {
        target[offset++] = (byte) (0x80 | (codePoint >>> shift & 0x3f));
      }
    }
    return offset - start;
  }

  static long hash(CharSequence value) {
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < value.length(); index++) {
      int codePoint = codePointAt(value, index);
      if (Character.isHighSurrogate(value.charAt(index))) index++;
      int width = width(codePoint);
      hash = update(hash, firstByte(codePoint, width));
      for (int shift = (width - 2) * 6; shift >= 0; shift -= 6) {
        hash = update(hash, 0x80 | (codePoint >>> shift & 0x3f));
      }
    }
    return hash;
  }

  static long hash(byte[] bytes, int offset, int length) {
    long hash = 0xcbf29ce484222325L;
    for (int index = 0; index < length; index++) {
      hash = update(hash, Byte.toUnsignedInt(bytes[offset + index]));
    }
    return hash;
  }

  static boolean equals(byte[] bytes, int offset, int length, CharSequence value) {
    int sourceBytes = length(value);
    if (sourceBytes != length) return false;
    int sourceOffset = 0;
    for (int index = 0; index < value.length(); index++) {
      int codePoint = codePointAt(value, index);
      if (Character.isHighSurrogate(value.charAt(index))) index++;
      int width = width(codePoint);
      int first = firstByte(codePoint, width);
      if (Byte.toUnsignedInt(bytes[offset + sourceOffset++]) != first) return false;
      for (int shift = (width - 2) * 6; shift >= 0; shift -= 6) {
        int expected = 0x80 | (codePoint >>> shift & 0x3f);
        if (Byte.toUnsignedInt(bytes[offset + sourceOffset++]) != expected) return false;
      }
    }
    return true;
  }

  private static int codePointAt(CharSequence value, int index) {
    char first = value.charAt(index);
    return Character.isHighSurrogate(first)
        ? Character.toCodePoint(first, value.charAt(index + 1)) : first;
  }

  private static int width(int codePoint) {
    return codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
  }

  private static int firstByte(int codePoint, int width) {
    return width == 1 ? codePoint : width == 2 ? 0xc0 | codePoint >>> 6
        : width == 3 ? 0xe0 | codePoint >>> 12 : 0xf0 | codePoint >>> 18;
  }

  private static long update(long hash, int value) {
    return (hash ^ value) * 0x100000001b3L;
  }
}
