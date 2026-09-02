package io.riverdb.base.type;

/** Correctly rounded decimal128-to-binary64 conversion over caller-owned scratch. */
final class ExactDecimal128ToDouble {
  private static final double TWO_TO_64 = 0x1.0p64;

  private ExactDecimal128ToDouble() { }

  static double convert(
      long high, long low, int scale, ExactDecimal128.Scratch scratch) {
    boolean negative = high < 0;
    long magnitudeLow = low;
    long magnitudeHigh = high;
    if (negative) {
      magnitudeLow = ~low + 1;
      magnitudeHigh = ~high + (magnitudeLow == 0 ? 1 : 0);
    }
    if (magnitudeHigh == 0 && magnitudeLow == 0) return 0.0d;
    long divisorHigh = ExactDecimal128Powers.high(scale);
    long divisorLow = ExactDecimal128Powers.low(scale);
    double estimate = unsignedDouble(magnitudeHigh, magnitudeLow)
        / unsignedDouble(divisorHigh, divisorLow);
    int exponent = Math.getExponent(estimate);
    long significand = roundedSignificand(
        magnitudeHigh, magnitudeLow, scale, exponent, scratch);
    if (Long.compareUnsigned(significand, 1L << 52) < 0) {
      exponent--;
      significand = roundedSignificand(
          magnitudeHigh, magnitudeLow, scale, exponent, scratch);
    }
    double converted = Math.scalb((double) significand, exponent - 52);
    return negative ? -converted : converted;
  }

  private static long roundedSignificand(
      long high, long low, int scale, int exponent,
      ExactDecimal128.Scratch scratch) {
    ExactDecimal128Wide.set128(high, low, scratch);
    ExactDecimal128Wide.setDivisor128(
        ExactDecimal128Powers.high(scale),
        ExactDecimal128Powers.low(scale), scratch);
    int shift = 52 - exponent;
    if (shift >= 0) ExactDecimal128ConversionWide.shiftValue(shift, scratch);
    else ExactDecimal128ConversionWide.shiftDivisor(-shift, scratch);
    ExactDecimal128WideDivide.divide(scratch);
    int half = ExactDecimal128ConversionWide.compareTwiceRemainder(scratch);
    if (half > 0 || half == 0 && (scratch.q0 & 1) != 0) scratch.q0++;
    return scratch.q0;
  }

  private static double unsignedDouble(long high, long low) {
    double unsignedLow = (double) (low & Long.MAX_VALUE)
        + (low < 0 ? 0x1.0p63 : 0.0d);
    return high * TWO_TO_64 + unsignedLow;
  }
}
