package io.riverdb.base.type;

import io.riverdb.base.type.ExactDecimal.WideScratch;

/** Shared unsigned 128-bit division kernel for compact decimal operations. */
final class ExactDecimalWideDivision {
  private ExactDecimalWideDivision() { }

  static void unsignedRemainder(
      long dividendHigh,
      long dividendLow,
      long divisorHigh,
      long divisorLow,
      WideScratch result) {
    long remainderHigh = 0;
    long remainderLow = 0;
    for (int bit = 127; bit >= 0; bit--) {
      remainderHigh = remainderHigh << 1 | remainderLow >>> 63;
      remainderLow = remainderLow << 1
          | (bit >= 64 ? dividendHigh >>> bit - 64 : dividendLow >>> bit) & 1;
      if (compareUnsigned(
          remainderHigh, remainderLow, divisorHigh, divisorLow) >= 0) {
        long nextLow = remainderLow - divisorLow;
        remainderHigh = remainderHigh - divisorHigh
            - (Long.compareUnsigned(remainderLow, divisorLow) < 0 ? 1 : 0);
        remainderLow = nextLow;
      }
    }
    result.high = remainderHigh;
    result.low = remainderLow;
  }

  static int compareUnsigned(
      long leftHigh, long leftLow, long rightHigh, long rightLow) {
    int high = Long.compareUnsigned(leftHigh, rightHigh);
    return high != 0 ? high : Long.compareUnsigned(leftLow, rightLow);
  }

  static boolean divideSigned(
      long high, long low, long divisor, WideScratch result) {
    boolean negative = high < 0;
    long magnitudeHigh = high;
    long magnitudeLow = low;
    if (negative) {
      magnitudeLow = ~low + 1;
      magnitudeHigh = ~high + (magnitudeLow == 0 ? 1 : 0);
    }
    if (!divideUnsigned(magnitudeHigh, magnitudeLow, divisor, result)) {
      return false;
    }
    long quotient = result.quotient;
    if ((!negative && quotient < 0)
        || (negative
            && Long.compareUnsigned(quotient, Long.MIN_VALUE) > 0)) {
      return false;
    }
    result.quotient = negative ? -quotient : quotient;
    result.negative = negative;
    return true;
  }

  static boolean divideUnsigned(
      long high, long low, long divisor, WideScratch result) {
    long quotient = 0;
    long remainder = 0;
    for (int bit = 127; bit >= 0; bit--) {
      long inputBit = bit >= 64
          ? high >>> bit - 64 & 1 : low >>> bit & 1;
      boolean carry = remainder < 0;
      remainder = remainder << 1 | inputBit;
      if (carry || Long.compareUnsigned(remainder, divisor) >= 0) {
        remainder -= divisor;
        if (bit >= 64) {
          return false;
        }
        quotient |= 1L << bit;
      }
    }
    result.quotient = quotient;
    result.remainder = remainder;
    return true;
  }
}
