package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Canonical fixed-width codecs for signed 128-bit exact decimal values. */
public final class ExactDecimal128Codec {
  public static final int BYTES = 16;

  private ExactDecimal128Codec() { }

  public static StatusCode encode(
      byte[] destination,
      int offset,
      int length,
      long high,
      long low,
      int precision) {
    if (!validRange(destination, offset, length) || length != BYTES
        || !ExactDecimal128.fits(high, low, precision)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    putLong(destination, offset, high);
    putLong(destination, offset + Long.BYTES, low);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      byte[] source,
      int offset,
      int length,
      int precision,
      ExactDecimal128.Value result) {
    if (result == null || !validRange(source, offset, length) || length != BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long high = getLong(source, offset);
    long low = getLong(source, offset + Long.BYTES);
    if (!ExactDecimal128.fits(high, low, precision)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.high = high;
    result.low = low;
    return StatusCode.OK;
  }

  /** Encodes bytes whose unsigned lexicographic order is the signed numeric order. */
  public static StatusCode encodeOrdered(
      byte[] destination,
      int offset,
      int length,
      long high,
      long low,
      int precision) {
    StatusCode status = encode(
        destination, offset, length, high, low, precision);
    if (!status.isOk()) return status;
    destination[offset] ^= (byte) 0x80;
    return StatusCode.OK;
  }

  public static StatusCode decodeOrdered(
      byte[] source,
      int offset,
      int length,
      int precision,
      ExactDecimal128.Value result) {
    if (result == null || !validRange(source, offset, length) || length != BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long high = getLong(source, offset) ^ Long.MIN_VALUE;
    long low = getLong(source, offset + Long.BYTES);
    if (!ExactDecimal128.fits(high, low, precision)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.high = high;
    result.low = low;
    return StatusCode.OK;
  }

  private static void putLong(byte[] bytes, int offset, long value) {
    for (int index = 0; index < Long.BYTES; index++) {
      bytes[offset + index] = (byte) (value >>> (Long.BYTES - index - 1) * Byte.SIZE);
    }
  }

  private static long getLong(byte[] bytes, int offset) {
    long value = 0;
    for (int index = 0; index < Long.BYTES; index++) {
      value = value << Byte.SIZE | bytes[offset + index] & 0xffL;
    }
    return value;
  }

  private static boolean validRange(byte[] bytes, int offset, int length) {
    return bytes != null && offset >= 0 && length >= 0 && offset <= bytes.length - length;
  }
}
