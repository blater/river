package io.riverdb.base.text;

import java.nio.ByteBuffer;

/** Strict, allocation-free UTF-8 operations over caller-owned storage. */
public final class Utf8Text {
  public static final int MAXIMUM_SCALARS = 255;
  public static final int MAXIMUM_BYTES = MAXIMUM_SCALARS * 4;

  private Utf8Text() {
  }

  public static int encodedLength(CharSequence value) {
    return Utf8TextEncoding.encodedLength(value);
  }

  public static int encodedLength(CharSequence value, int maximumScalars) {
    return Utf8TextEncoding.encodedLength(value, maximumScalars);
  }

  public static int scalarCount(CharSequence value) {
    return Utf8TextEncoding.scalarCount(value);
  }

  public static int encodedLength(char[] value, int offset, int length, int maximumScalars) {
    return Utf8TextEncoding.encodedLength(value, offset, length, maximumScalars);
  }

  public static int scalarCount(char[] value, int offset, int length) {
    return Utf8TextEncoding.scalarCount(value, offset, length);
  }

  public static int encode(CharSequence value, int maximumScalars, ByteBuffer target) {
    return Utf8TextEncoding.encode(value, maximumScalars, target);
  }

  public static int encode(CharSequence value, ByteBuffer target) {
    return Utf8TextEncoding.encode(value, target);
  }

  public static int encode(char[] value, int valueOffset, int valueLength, int maximumScalars,
      byte[] target, int targetOffset) {
    return Utf8TextEncoding.encode(value, valueOffset, valueLength, maximumScalars,
        target, targetOffset);
  }

  public static int validate(ByteBuffer source, int offset, int length, int maximumScalars) {
    return Utf8TextDecoding.validate(source, offset, length, maximumScalars);
  }

  public static int validate(ByteBuffer source, int offset, int length) {
    return Utf8TextDecoding.validate(source, offset, length);
  }

  public static int decode(ByteBuffer source, int offset, int length, char[] target,
      int targetOffset) {
    return Utf8TextDecoding.decode(source, offset, length, target, targetOffset);
  }

  /** Unicode-code-point order; canonical UTF-8 preserves this under unsigned byte order. */
  public static int compare(ByteBuffer left, int leftOffset, int leftLength,
      ByteBuffer right, int rightOffset, int rightLength) {
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(Byte.toUnsignedInt(left.get(leftOffset + index)),
          Byte.toUnsignedInt(right.get(rightOffset + index)));
      if (comparison != 0) return comparison;
    }
    return Integer.compare(leftLength, rightLength);
  }
}
