package io.riverdb.base.type;

/** Allocation-free unsigned and signed pair operations for {@link ExactDecimal128}. */
final class ExactDecimal128Math {
  private ExactDecimal128Math() { }

  static int compareSigned(
      long leftHigh, long leftLow, long rightHigh, long rightLow) {
    int high = Long.compare(leftHigh, rightHigh);
    return high != 0 ? high : Long.compareUnsigned(leftLow, rightLow);
  }

  static int compareUnsigned(
      long leftHigh, long leftLow, long rightHigh, long rightLow) {
    int high = Long.compareUnsigned(leftHigh, rightHigh);
    return high != 0 ? high : Long.compareUnsigned(leftLow, rightLow);
  }

  static void magnitude(
      long high, long low, ExactDecimal128.Scratch result) {
    if (high >= 0) {
      result.high = high;
      result.low = low;
      return;
    }
    result.low = ~low + 1;
    result.high = ~high + (result.low == 0 ? 1 : 0);
  }

  static void signed(
      long high, long low, boolean negative, ExactDecimal128.Scratch result) {
    if (!negative || high == 0 && low == 0) {
      result.high = high;
      result.low = low;
      return;
    }
    result.low = ~low + 1;
    result.high = ~high + (result.low == 0 ? 1 : 0);
  }

  static boolean multiplyPower(
      long high, long low, int exponent, ExactDecimal128.Scratch result) {
    if (!ExactDecimal128Powers.valid(exponent)) return false;
    long productHigh = high;
    long productLow = low;
    for (int index = 0; index < exponent; index++) {
      long carry = unsignedMultiplyHigh(productLow, 10);
      long maximumHigh = Long.divideUnsigned(-1L - carry, 10);
      if (Long.compareUnsigned(productHigh, maximumHigh) > 0) return false;
      productHigh = productHigh * 10 + carry;
      productLow *= 10;
    }
    result.high = productHigh;
    result.low = productLow;
    return true;
  }

  static boolean addSigned(
      long leftHigh,
      long leftLow,
      long rightHigh,
      long rightLow,
      ExactDecimal128.Scratch result) {
    long low = leftLow + rightLow;
    long high = leftHigh + rightHigh
        + (Long.compareUnsigned(low, leftLow) < 0 ? 1 : 0);
    if (((leftHigh ^ high) & (rightHigh ^ high)) < 0) return false;
    result.high = high;
    result.low = low;
    return true;
  }

  static boolean subtractUnsigned(
      long leftHigh,
      long leftLow,
      long rightHigh,
      long rightLow,
      ExactDecimal128.Scratch result) {
    if (compareUnsigned(leftHigh, leftLow, rightHigh, rightLow) < 0) return false;
    result.low = leftLow - rightLow;
    result.high = leftHigh - rightHigh
        - (Long.compareUnsigned(leftLow, rightLow) < 0 ? 1 : 0);
    return true;
  }

  static void divideUnsigned(
      long dividendHigh,
      long dividendLow,
      long divisorHigh,
      long divisorLow,
      ExactDecimal128.Scratch result) {
    long quotientHigh = 0;
    long quotientLow = 0;
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
        if (bit >= 64) quotientHigh |= 1L << bit - 64;
        else quotientLow |= 1L << bit;
      }
    }
    result.high = quotientHigh;
    result.low = quotientLow;
    result.remainderHigh = remainderHigh;
    result.remainderLow = remainderLow;
  }

  static boolean incrementUnsigned(ExactDecimal128.Scratch value) {
    long low = value.low + 1;
    long high = value.high + (low == 0 ? 1 : 0);
    if (high == 0 && low == 0) return false;
    value.high = high;
    value.low = low;
    return true;
  }

  private static long unsignedMultiplyHigh(long left, long right) {
    return Math.multiplyHigh(left, right)
        + (left < 0 ? right : 0)
        + (right < 0 ? left : 0);
  }
}
