package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

final class ExactDecimal128CodecTest {
  @Test
  void canonicalCodecRoundTripsBoundariesAndPreservesOutputOnFailure() {
    BigInteger maximum = BigInteger.TEN.pow(38).subtract(BigInteger.ONE);
    assertRoundTrip(maximum);
    assertRoundTrip(maximum.negate());
    assertRoundTrip(BigInteger.ZERO);

    ExactDecimal128.Value result = pair(BigInteger.valueOf(71));
    byte[] outOfDomain = bytes(BigInteger.TEN.pow(38));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        ExactDecimal128Codec.decode(outOfDomain, 0, outOfDomain.length, 38, result));
    assertEquals(BigInteger.valueOf(71), integer(result));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT,
        ExactDecimal128Codec.decode(outOfDomain, 1, outOfDomain.length, 38, result));
  }

  @Test
  void orderedCodecHasUnsignedLexicographicNumericOrder() {
    BigInteger[] values = {
        BigInteger.TEN.pow(38).subtract(BigInteger.ONE).negate(),
        BigInteger.valueOf(-1),
        BigInteger.ZERO,
        BigInteger.ONE,
        BigInteger.TEN.pow(38).subtract(BigInteger.ONE)
    };
    byte[] previous = null;
    for (BigInteger value : values) {
      ExactDecimal128.Value pair = pair(value);
      byte[] encoded = new byte[ExactDecimal128Codec.BYTES];
      assertEquals(StatusCode.OK, ExactDecimal128Codec.encodeOrdered(
          encoded, 0, encoded.length, pair.high, pair.low, 38));
      if (previous != null) assertTrue(compareUnsigned(previous, encoded) < 0);
      ExactDecimal128.Value decoded = new ExactDecimal128.Value();
      assertEquals(StatusCode.OK, ExactDecimal128Codec.decodeOrdered(
          encoded, 0, encoded.length, 38, decoded));
      assertEquals(value, integer(decoded));
      previous = encoded;
    }
  }

  private static void assertRoundTrip(BigInteger expected) {
    ExactDecimal128.Value source = pair(expected);
    byte[] encoded = new byte[ExactDecimal128Codec.BYTES + 2];
    assertEquals(StatusCode.OK, ExactDecimal128Codec.encode(
        encoded, 1, ExactDecimal128Codec.BYTES, source.high, source.low, 38));
    ExactDecimal128.Value decoded = new ExactDecimal128.Value();
    assertEquals(StatusCode.OK, ExactDecimal128Codec.decode(
        encoded, 1, ExactDecimal128Codec.BYTES, 38, decoded));
    assertEquals(expected, integer(decoded));
  }

  private static ExactDecimal128.Value pair(BigInteger value) {
    byte[] encoded = bytes(value);
    ExactDecimal128.Value result = new ExactDecimal128.Value();
    result.high = readLong(encoded, 0);
    result.low = readLong(encoded, Long.BYTES);
    return result;
  }

  private static byte[] bytes(BigInteger value) {
    byte[] source = value.toByteArray();
    byte[] result = new byte[ExactDecimal128Codec.BYTES];
    byte fill = value.signum() < 0 ? (byte) 0xff : 0;
    for (int index = 0; index < result.length; index++) result[index] = fill;
    int copied = Math.min(source.length, result.length);
    System.arraycopy(source, source.length - copied, result, result.length - copied, copied);
    return result;
  }

  private static BigInteger integer(ExactDecimal128.Value value) {
    byte[] result = new byte[ExactDecimal128Codec.BYTES];
    writeLong(result, 0, value.high);
    writeLong(result, Long.BYTES, value.low);
    return new BigInteger(result);
  }

  private static int compareUnsigned(byte[] left, byte[] right) {
    for (int index = 0; index < left.length; index++) {
      int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
      if (comparison != 0) return comparison;
    }
    return 0;
  }

  private static long readLong(byte[] bytes, int offset) {
    long value = 0;
    for (int index = 0; index < Long.BYTES; index++) {
      value = value << 8 | bytes[offset + index] & 0xffL;
    }
    return value;
  }

  private static void writeLong(byte[] bytes, int offset, long value) {
    for (int index = 0; index < Long.BYTES; index++) {
      bytes[offset + index] = (byte) (value >>> (7 - index) * 8);
    }
  }
}
