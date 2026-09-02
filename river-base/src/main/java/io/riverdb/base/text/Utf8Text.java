package io.riverdb.base.text;

import java.nio.ByteBuffer;

/** Strict, allocation-free UTF-8 operations over caller-owned storage. */
public final class Utf8Text {
  /** Maximum declared VARCHAR length accepted by the UTF-8 primitives. */
  public static final int MAXIMUM_SCALARS = 65_535;
  /** Maximum byte capacity used by bounded row/value scratch buffers. */
  public static final int MAXIMUM_BYTES = 8 * 1_024;
  /** Bound for fixed parser/result scratch, independent of a column declaration. */
  public static final int MAXIMUM_BUFFER_CHARACTERS = 8_192;
  public static final int MAXIMUM_BUFFER_BYTES = MAXIMUM_BYTES;

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

  /** Returns the decoded UTF-16 code-unit count, or {@code -1} for non-canonical UTF-8. */
  public static int decodedLength(ByteBuffer source, int offset, int length) {
    return Utf8TextDecoding.decodedLength(source, offset, length);
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
