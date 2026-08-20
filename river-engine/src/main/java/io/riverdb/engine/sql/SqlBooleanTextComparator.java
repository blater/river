package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;

/** Compares owned predicate text by Unicode scalar value without allocation. */
final class SqlBooleanTextComparator {
  private SqlBooleanTextComparator() {
  }

  static int compare(SqlPredicateOperand left, SqlPredicateOperand right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.textLength() && rightIndex < right.textLength()) {
      int leftScalar = codePoint(left, leftIndex);
      int rightScalar = codePoint(right, rightIndex);
      if (leftScalar != rightScalar) return Integer.compare(leftScalar, rightScalar);
      leftIndex += Character.charCount(leftScalar);
      rightIndex += Character.charCount(rightScalar);
    }
    return Integer.compare(
        left.textLength() - leftIndex, right.textLength() - rightIndex);
  }

  static int compareLiteral(
      SqlPredicateOperand left, SqlCommand source, long handle) {
    int byteLength = source.textByteLength(handle);
    if (byteLength < 0) return Integer.MIN_VALUE;
    int charIndex = 0;
    int byteIndex = 0;
    while (charIndex < left.textLength() && byteIndex < byteLength) {
      int leftScalar = codePoint(left, charIndex);
      int packed = decode(source, handle, byteIndex, byteLength);
      if (packed < 0) return Integer.MIN_VALUE;
      int rightScalar = packed & 0x1f_ffff;
      if (leftScalar != rightScalar) return Integer.compare(leftScalar, rightScalar);
      charIndex += Character.charCount(leftScalar);
      byteIndex += packed >>> 24;
    }
    return Integer.compare(left.textLength() - charIndex, byteLength - byteIndex);
  }

  private static int decode(
      SqlCommand source, long handle, int index, int length) {
    int first = Byte.toUnsignedInt(source.textByteAt(handle, index));
    if (first <= 0x7f) return 1 << 24 | first;
    int bytes = first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
    if (index > length - bytes) return -1;
    int scalar = first & (0x7f >> bytes);
    for (int offset = 1; offset < bytes; offset++) {
      int next = Byte.toUnsignedInt(source.textByteAt(handle, index + offset));
      if ((next & 0xc0) != 0x80) return -1;
      scalar = scalar << 6 | next & 0x3f;
    }
    return bytes << 24 | scalar;
  }

  private static int codePoint(SqlPredicateOperand operand, int index) {
    char first = operand.textCharacter(index);
    return Character.isHighSurrogate(first)
        ? Character.toCodePoint(first, operand.textCharacter(index + 1)) : first;
  }
}
