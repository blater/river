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
    if (source == null || target == null || offset < 0 || length < 0
        || offset > source.length() - length) return -1;
    int input = 0;
    int output = 0;
    while (input < length) {
      int first = Byte.toUnsignedInt(source.getByte(offset + input++));
      int continuation = continuation(first);
      if (continuation < 0 || input + continuation > length) return -1;
      int scalar = firstScalarBits(first, continuation);
      for (int index = 0; index < continuation; index++) {
        int next = Byte.toUnsignedInt(source.getByte(offset + input++));
        if ((next & 0xc0) != 0x80) return -1;
        scalar = scalar << 6 | next & 0x3f;
      }
      if (!canonical(scalar, continuation)) return -1;
      if (scalar <= Character.MAX_VALUE) {
        if (output >= target.length) return -1;
        target[output++] = (char) scalar;
      } else {
        if (output + 1 >= target.length) return -1;
        target[output++] = Character.highSurrogate(scalar);
        target[output++] = Character.lowSurrogate(scalar);
      }
    }
    return output;
  }

  private static int continuation(int first) {
    if (first < 0x80) return 0;
    if ((first & 0xe0) == 0xc0) return 1;
    if ((first & 0xf0) == 0xe0) return 2;
    return (first & 0xf8) == 0xf0 ? 3 : -1;
  }

  private static int firstScalarBits(int first, int continuation) {
    return switch (continuation) {
      case 0 -> first;
      case 1 -> first & 0x1f;
      case 2 -> first & 0x0f;
      default -> first & 0x07;
    };
  }

  private static boolean canonical(int scalar, int continuation) {
    return continuation == 0
        || continuation == 1 && scalar >= 0x80
        || continuation == 2 && scalar >= 0x800
            && (scalar < 0xd800 || scalar > 0xdfff)
        || continuation == 3 && scalar >= 0x10000 && scalar <= 0x10ffff;
  }
}
