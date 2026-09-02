package io.riverdb.base.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class ExactDecimal128Test {
  private final ExactDecimal128.Value result = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch scratch = new ExactDecimal128.Scratch();

  @Test
  void admitsExactlySignedDecimal38Domain() {
    BigInteger limit = BigInteger.TEN.pow(38);
    ExactDecimal128.Value maximum = value(limit.subtract(BigInteger.ONE));
    ExactDecimal128.Value minimum = value(limit.subtract(BigInteger.ONE).negate());
    ExactDecimal128.Value positiveLimit = value(limit);
    ExactDecimal128.Value negativeLimit = value(limit.negate());

    assertTrue(ExactDecimal128.fits(maximum.high, maximum.low, 38));
    assertTrue(ExactDecimal128.fits(minimum.high, minimum.low, 38));
    assertFalse(ExactDecimal128.fits(positiveLimit.high, positiveLimit.low, 38));
    assertFalse(ExactDecimal128.fits(negativeLimit.high, negativeLimit.low, 38));
    assertFalse(ExactDecimal128.fits(Long.MIN_VALUE, 0, 38));
    assertFalse(ExactDecimal128.fits(0, 0, 0));
    assertFalse(ExactDecimal128.fits(0, 0, 39));
  }

  @Test
  void addsMixedScalesAndPreservesResultOnOverflow() {
    ExactDecimal128.Value left = value(new BigInteger("999999999999999999999999999999999999"));
    ExactDecimal128.Value right = value(BigInteger.valueOf(5));
    assertEquals(StatusCode.OK, ExactDecimal128.add(
        left.high, left.low, 36, 2,
        right.high, right.low, 1, 0,
        false, 38, 2, result, scratch));
    assertValue(
        new BigInteger("1000000000000000000000000000000000499"), result);

    ExactDecimal128.Value maximum = value(BigInteger.TEN.pow(38).subtract(BigInteger.ONE));
    result.high = 71;
    result.low = 73;
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, ExactDecimal128.add(
        maximum.high, maximum.low, 38, 0,
        0, 1, 1, 0,
        false, 38, 0, result, scratch));
    assertEquals(71, result.high);
    assertEquals(73, result.low);
  }

  @Test
  void quantizesWithExplicitRoundingAndExactness() {
    ExactDecimal128.Value source = value(new BigInteger("123456789012345678901234567890125"));
    assertEquals(StatusCode.OK, ExactDecimal128.quantize(
        source.high, source.low, 36, 3,
        35, 2, ExactDecimal128.ROUND_HALF_EVEN, false, result, scratch));
    assertValue(new BigInteger("12345678901234567890123456789012"), result);

    source = value(new BigInteger("123456789012345678901234567890135"));
    assertEquals(StatusCode.OK, ExactDecimal128.quantize(
        source.high, source.low, 36, 3,
        35, 2, ExactDecimal128.ROUND_HALF_EVEN, false, result, scratch));
    assertValue(new BigInteger("12345678901234567890123456789014"), result);

    source = value(new BigInteger("-123456789012345678901234567890125"));
    assertEquals(StatusCode.OK, ExactDecimal128.quantize(
        source.high, source.low, 36, 3,
        35, 2, ExactDecimal128.ROUND_HALF_AWAY, false, result, scratch));
    assertValue(new BigInteger("-12345678901234567890123456789013"), result);

    result.high = 11;
    result.low = 13;
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, ExactDecimal128.quantize(
        source.high, source.low, 36, 3,
        35, 2, ExactDecimal128.ROUND_TRUNCATE, true, result, scratch));
    assertEquals(11, result.high);
    assertEquals(13, result.low);
  }

  @Test
  void widensLongsAndComparesDifferentScales() {
    assertEquals(StatusCode.OK,
        ExactDecimal128.fromLong(Long.MIN_VALUE, 38, 18, result, scratch));
    assertValue(
        BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.TEN.pow(18)), result);

    ExactDecimal128.Value one = value(BigInteger.ONE);
    ExactDecimal128.Value scaledOne = value(BigInteger.TEN.pow(37));
    assertEquals(0, ExactDecimal128.compare(
        one.high, one.low, 0,
        scaledOne.high, scaledOne.low, 37, scratch));

    ExactDecimal128.Value lower = value(BigInteger.TEN.pow(37).subtract(BigInteger.ONE));
    assertTrue(ExactDecimal128.compare(
        one.high, one.low, 0,
        lower.high, lower.low, 37, scratch) > 0);
    ExactDecimal128.Value negativeLower = value(
        BigInteger.TEN.pow(37).subtract(BigInteger.ONE).negate());
    ExactDecimal128.Value negativeOne = value(BigInteger.ONE.negate());
    assertTrue(ExactDecimal128.compare(
        negativeOne.high, negativeOne.low, 0,
        negativeLower.high, negativeLower.low, 37, scratch) < 0);
  }

  @Test
  void multipliesWithAFullWidthIntermediateAndHalfEvenRounding() {
    ExactDecimal128.Value left = value(
        new BigInteger("99999999999999999999999999999999999999"));
    ExactDecimal128.Value right = value(
        new BigInteger("99999999999999999999999999999999999999"));
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.multiply(
        left.high, left.low, 38, 19,
        right.high, right.low, 38, 19,
        38, 0, result, scratch));
    assertValue(
        new BigInteger("99999999999999999999999999999999999998"), result);

    left = value(BigInteger.valueOf(25));
    right = value(BigInteger.ONE);
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.multiply(
        left.high, left.low, 2, 1,
        right.high, right.low, 1, 0,
        1, 0, result, scratch));
    assertValue(BigInteger.valueOf(2), result);
    left = value(BigInteger.valueOf(35));
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.multiply(
        left.high, left.low, 2, 1,
        right.high, right.low, 1, 0,
        1, 0, result, scratch));
    assertValue(BigInteger.valueOf(4), result);
  }

  @Test
  void dividesAndPreservesResultForZeroAndOverflow() {
    ExactDecimal128.Value one = value(BigInteger.ONE);
    ExactDecimal128.Value eight = value(BigInteger.valueOf(8));
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.divide(
        one.high, one.low, 1, 0,
        eight.high, eight.low, 1, 0,
        38, 38, result, scratch));
    assertValue(new BigInteger("12500000000000000000000000000000000000"), result);

    ExactDecimal128.Value fortyFour = value(BigInteger.valueOf(44));
    ExactDecimal128.Value sixteen = value(BigInteger.valueOf(16));
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.divide(
        fortyFour.high, fortyFour.low, 2, 1,
        sixteen.high, sixteen.low, 2, 1,
        2, 1, result, scratch));
    assertValue(BigInteger.valueOf(28), result);

    result.high = 41;
    result.low = 43;
    assertEquals(StatusCode.DIVISION_BY_ZERO, ExactDecimal128Arithmetic.divide(
        one.high, one.low, 1, 0,
        0, 0, 1, 0,
        38, 0, result, scratch));
    assertEquals(41, result.high);
    assertEquals(43, result.low);
    ExactDecimal128.Value maximum = value(BigInteger.TEN.pow(38).subtract(BigInteger.ONE));
    assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, ExactDecimal128Arithmetic.divide(
        maximum.high, maximum.low, 38, 0,
        one.high, one.low, 38, 38,
        38, 0, result, scratch));
    assertEquals(41, result.high);
    assertEquals(43, result.low);
  }

  @Test
  void computesSignedRemainderFloorCeilingAndAverageDivision() {
    ExactDecimal128.Value negative = value(BigInteger.valueOf(-1234));
    ExactDecimal128.Value divisor = value(BigInteger.valueOf(50));
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.remainder(
        negative.high, negative.low, 4, 2,
        divisor.high, divisor.low, 2, 1,
        3, 2, result, scratch));
    assertValue(BigInteger.valueOf(-234), result);

    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.floor(
        negative.high, negative.low, 4, 2, 2, result, scratch));
    assertValue(BigInteger.valueOf(-13), result);
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.ceiling(
        negative.high, negative.low, 4, 2, 2, result, scratch));
    assertValue(BigInteger.valueOf(-12), result);

    ExactDecimal128.Value sum = value(BigInteger.valueOf(10));
    assertEquals(StatusCode.OK, ExactDecimal128Arithmetic.divideByLong(
        sum.high, sum.low, 2, 1, 4, 2, 1, result, scratch));
    assertValue(BigInteger.valueOf(2), result);
  }

  @Test
  void randomScaleConversionMatchesBigDecimal() {
    Random random = new Random(0x444543313238L);
    BigInteger limit = BigInteger.TEN.pow(38);
    for (int iteration = 0; iteration < 2_000; iteration++) {
      BigInteger unscaled = new BigInteger(126, random).mod(limit);
      if (random.nextBoolean()) unscaled = unscaled.negate();
      int sourceScale = random.nextInt(39);
      int targetScale = random.nextInt(39);
      int targetPrecision = Math.max(1, targetScale);
      targetPrecision = Math.max(targetPrecision, random.nextInt(39));
      ExactDecimal128.Value source = value(unscaled);
      StatusCode status = ExactDecimal128.quantize(
          source.high, source.low, 38, sourceScale,
          targetPrecision, targetScale,
          ExactDecimal128.ROUND_HALF_EVEN, false, result, scratch);
      BigDecimal expected = new BigDecimal(unscaled, sourceScale)
          .setScale(targetScale, RoundingMode.HALF_EVEN);
      boolean fits = expected.unscaledValue().abs()
          .compareTo(BigInteger.TEN.pow(targetPrecision)) < 0;
      assertEquals(fits ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, status,
          "iteration " + iteration);
      if (fits) assertValue(expected.unscaledValue(), result);
    }
  }

  @Test
  void decimalToDoubleMatchesBigDecimalAcrossEveryScale() {
    ExactDecimal128.Scratch conversionScratch = new ExactDecimal128.Scratch();
    BigInteger limit = BigInteger.TEN.pow(38);
    BigInteger[] ties = {
        BigInteger.valueOf(9_007_199_254_740_993L),
        BigInteger.valueOf(9_007_199_254_740_995L),
        BigInteger.valueOf(-9_007_199_254_740_993L),
        BigInteger.valueOf(-9_007_199_254_740_995L)
    };
    for (BigInteger unscaled : ties) {
      assertDouble(unscaled, 0, conversionScratch);
    }
    Random random = new Random(0x44313238544f444cL);
    for (int scale = 0; scale <= 38; scale++) {
      assertDouble(BigInteger.ONE, scale, conversionScratch);
      assertDouble(limit.subtract(BigInteger.ONE), scale, conversionScratch);
      assertDouble(limit.subtract(BigInteger.ONE).negate(), scale, conversionScratch);
      for (int iteration = 0; iteration < 100; iteration++) {
        BigInteger unscaled = new BigInteger(127, random).mod(limit);
        if (random.nextBoolean()) unscaled = unscaled.negate();
        assertDouble(unscaled, scale, conversionScratch);
      }
    }
  }

  @Test
  void doubleToDecimalMatchesExactBinaryHalfEvenOracle() {
    double[] boundaries = {
        0.0d,
        -0.0d,
        0.5d,
        1.5d,
        2.5d,
        2469.0d,
        0.1d,
        -0.1d,
        Double.MIN_VALUE,
        -Double.MIN_VALUE,
        Double.MIN_NORMAL,
        -Double.MIN_NORMAL,
        Math.nextDown(1.0e38d),
        -Math.nextDown(1.0e38d),
        1.0e38d,
        -1.0e38d,
        Math.scalb(1.0d, 127),
        -Math.scalb(1.0d, 127),
        Double.MAX_VALUE,
        -Double.MAX_VALUE,
        Double.POSITIVE_INFINITY,
        Double.NaN
    };
    for (int scale = 0; scale <= 38; scale++) {
      for (double value : boundaries) assertFromDouble(value, scale);
    }
    Random random = new Random(0x4431323846524f4dL);
    for (int iteration = 0; iteration < 2_000; iteration++) {
      double value = Double.longBitsToDouble(random.nextLong());
      assertFromDouble(value, iteration % 39);
    }
  }

  private static void assertDouble(
      BigInteger unscaled, int scale, ExactDecimal128.Scratch conversionScratch) {
    ExactDecimal128.Value source = value(unscaled);
    double expected = new BigDecimal(unscaled, scale).doubleValue();
    double actual = ExactDecimal128Conversion.toDouble(
        source.high, source.low, scale, conversionScratch);
    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    assertEquals(true, Double.isFinite(actual));
    if (actual != 0.0d) assertEquals(true, Math.abs(actual) >= Double.MIN_NORMAL);
  }

  private void assertFromDouble(double value, int scale) {
    StatusCode status = ExactDecimal128Conversion.fromDouble(
        value, 38, scale, result, scratch);
    if (!Double.isFinite(value)) {
      assertEquals(StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, status);
      return;
    }
    // BigDecimal(double) preserves the stored IEEE value rather than its display spelling.
    BigInteger expected = new BigDecimal(value)
        .setScale(scale, RoundingMode.HALF_EVEN).unscaledValue();
    boolean fits = expected.abs().compareTo(BigInteger.TEN.pow(38)) < 0;
    assertEquals(fits ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, status);
    if (!fits) return;
    ExactDecimal128.Value expectedValue = value(expected);
    assertEquals(expectedValue.high, result.high);
    assertEquals(expectedValue.low, result.low);
  }

  @Test
  void randomMultiplyAndDivideMatchBigDecimal() {
    Random random = new Random(0x4d554c444956L);
    BigInteger limit = BigInteger.TEN.pow(38);
    for (int iteration = 0; iteration < 1_000; iteration++) {
      BigInteger leftUnscaled = new BigInteger(126, random).mod(limit);
      BigInteger rightUnscaled = new BigInteger(126, random).mod(limit);
      if (random.nextBoolean()) leftUnscaled = leftUnscaled.negate();
      if (random.nextBoolean()) rightUnscaled = rightUnscaled.negate();
      int leftScale = random.nextInt(39);
      int rightScale = random.nextInt(39);
      int targetScale = random.nextInt(39);
      int targetPrecision = Math.max(targetScale, random.nextInt(39));
      targetPrecision = Math.max(1, targetPrecision);
      ExactDecimal128.Value left = value(leftUnscaled);
      ExactDecimal128.Value right = value(rightUnscaled);
      assertOperation(
          new BigDecimal(leftUnscaled, leftScale)
              .multiply(new BigDecimal(rightUnscaled, rightScale)),
          ExactDecimal128Arithmetic.multiply(
              left.high, left.low, 38, leftScale,
              right.high, right.low, 38, rightScale,
              targetPrecision, targetScale, result, scratch),
          targetPrecision, targetScale, iteration, "multiply");
      if (rightUnscaled.signum() != 0) {
        assertOperation(
            new BigDecimal(leftUnscaled, leftScale).divide(
                new BigDecimal(rightUnscaled, rightScale),
                targetScale, RoundingMode.HALF_EVEN),
            ExactDecimal128Arithmetic.divide(
                left.high, left.low, 38, leftScale,
                right.high, right.low, 38, rightScale,
                targetPrecision, targetScale, result, scratch),
            targetPrecision, targetScale, iteration, "divide");
      }
    }
  }

  private void assertOperation(
      BigDecimal exact,
      StatusCode status,
      int targetPrecision,
      int targetScale,
      int iteration,
      String operation) {
    BigDecimal expected = exact.setScale(targetScale, RoundingMode.HALF_EVEN);
    boolean fits = expected.unscaledValue().abs()
        .compareTo(BigInteger.TEN.pow(targetPrecision)) < 0;
    assertEquals(fits ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE, status,
        operation + " iteration " + iteration);
    if (fits) assertValue(expected.unscaledValue(), result);
  }

  private static ExactDecimal128.Value value(BigInteger value) {
    byte[] source = value.toByteArray();
    byte fill = value.signum() < 0 ? (byte) 0xff : 0;
    byte[] encoded = new byte[ExactDecimal128Codec.BYTES];
    for (int index = 0; index < encoded.length; index++) encoded[index] = fill;
    int copied = Math.min(source.length, encoded.length);
    System.arraycopy(
        source, source.length - copied, encoded, encoded.length - copied, copied);
    ExactDecimal128.Value result = new ExactDecimal128.Value();
    result.high = readLong(encoded, 0);
    result.low = readLong(encoded, Long.BYTES);
    return result;
  }

  private static void assertValue(BigInteger expected, ExactDecimal128.Value actual) {
    assertEquals(expected, bigInteger(actual.high, actual.low));
  }

  private static BigInteger bigInteger(long high, long low) {
    byte[] encoded = new byte[ExactDecimal128Codec.BYTES];
    writeLong(encoded, 0, high);
    writeLong(encoded, Long.BYTES, low);
    return new BigInteger(encoded);
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
