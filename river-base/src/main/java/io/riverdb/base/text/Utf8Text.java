package io.riverdb.base.text;

import java.nio.ByteBuffer;

/** Strict, allocation-free UTF-8 operations over caller-owned storage. */
public final class Utf8Text {
  /** Maximum declared VARCHAR length accepted by the UTF-8 primitives. */
  public static final int MAXIMUM_SCALARS = 65_535;
  /** Maximum UTF-16 code units needed to represent one declared VARCHAR value. */
  public static final int MAXIMUM_UTF16_CODE_UNITS = MAXIMUM_SCALARS * 2;
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

  /** Unicode-code-point order for two admitted UTF-8 slices. */
  public static int compare(
      BoundedByteSource left, int leftOffset, int leftLength,
      BoundedByteSource right, int rightOffset, int rightLength) {
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(left.getByte(leftOffset + index)),
          Byte.toUnsignedInt(right.getByte(rightOffset + index)));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftLength, rightLength);
  }

  /** Counts UTF-16 units in UTF-8 that was validated before this call. */
  public static int trustedUtf16Length(
      BoundedByteSource source, int offset, int length) {
    int characters = 0;
    for (int index = 0; index < length;) {
      int first = Byte.toUnsignedInt(source.getByte(offset + index));
      int width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
      characters += width == 4 ? 2 : 1;
      index += width;
    }
    return characters;
  }

  /** Counts scalars in UTF-8 admitted by an earlier owner. */
  public static int trustedScalarCount(
      BoundedByteSource source, int offset, int length) {
    int scalars = 0;
    for (int index = 0; index < length; index++) {
      if ((source.getByte(offset + index) & 0xc0) != 0x80) scalars++;
    }
    return scalars;
  }

  /** Reads one UTF-16 unit from UTF-8 that was validated before this call. */
  public static char trustedUtf16Character(
      BoundedByteSource source, int offset, int length, int target) {
    int character = 0;
    for (int index = 0; index < length;) {
      int first = Byte.toUnsignedInt(source.getByte(offset + index));
      int width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
      if (width > length - index) return 0;
      int scalar = width == 1 ? first : first & (0x7f >> width);
      for (int byteIndex = 1; byteIndex < width; byteIndex++) {
        scalar = scalar << 6
            | Byte.toUnsignedInt(source.getByte(offset + index + byteIndex)) & 0x3f;
      }
      if (width < 4) {
        if (character == target) return (char) scalar;
        character++;
      } else {
        if (character == target) return Character.highSurrogate(scalar);
        if (character + 1 == target) return Character.lowSurrogate(scalar);
        character += 2;
      }
      index += width;
    }
    return 0;
  }

  /** Decodes admitted UTF-8 into a caller-owned UTF-16 buffer. */
  public static int trustedDecode(
      byte[] source, int offset, int length, char[] target, int targetOffset) {
    int written = 0;
    for (int index = 0; index < length;) {
      int first = Byte.toUnsignedInt(source[offset + index]);
      int width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
      if (width > length - index) return -1;
      int scalar = width == 1 ? first : first & (0x7f >> width);
      for (int byteIndex = 1; byteIndex < width; byteIndex++) {
        scalar = scalar << 6 | source[offset + index + byteIndex] & 0x3f;
      }
      if (width < 4) {
        if (targetOffset + written >= target.length) return -1;
        target[targetOffset + written++] = (char) scalar;
      } else {
        if (targetOffset + written > target.length - 2) return -1;
        target[targetOffset + written++] = Character.highSurrogate(scalar);
        target[targetOffset + written++] = Character.lowSurrogate(scalar);
      }
      index += width;
    }
    return written;
  }
}
