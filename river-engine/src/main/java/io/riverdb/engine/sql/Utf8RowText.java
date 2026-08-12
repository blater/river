package io.riverdb.engine.sql;

import io.riverdb.storage.heap.HeapRowResult;

/** Decodes an already-validated row text range into caller-owned UTF-16 storage. */
final class Utf8RowText {
  private Utf8RowText() {
  }

  static int decode(
      HeapRowResult source,
      int offset,
      int length,
      char[] target) {
    int input = 0;
    int output = 0;
    while (input < length) {
      int first = Byte.toUnsignedInt(source.getByte(offset + input++));
      int scalar;
      if (first <= 0x7f) {
        scalar = first;
      } else if (first <= 0xdf) {
        int second = Byte.toUnsignedInt(source.getByte(offset + input++));
        scalar = (first & 0x1f) << 6 | second & 0x3f;
      } else if (first <= 0xef) {
        int second = Byte.toUnsignedInt(source.getByte(offset + input++));
        int third = Byte.toUnsignedInt(source.getByte(offset + input++));
        scalar = (first & 0x0f) << 12 | (second & 0x3f) << 6 | third & 0x3f;
      } else {
        int second = Byte.toUnsignedInt(source.getByte(offset + input++));
        int third = Byte.toUnsignedInt(source.getByte(offset + input++));
        int fourth = Byte.toUnsignedInt(source.getByte(offset + input++));
        scalar = (first & 0x07) << 18
            | (second & 0x3f) << 12
            | (third & 0x3f) << 6
            | fourth & 0x3f;
      }
      if (scalar <= Character.MAX_VALUE) {
        target[output++] = (char) scalar;
      } else {
        target[output++] = Character.highSurrogate(scalar);
        target[output++] = Character.lowSurrogate(scalar);
      }
    }
    return output;
  }
}
