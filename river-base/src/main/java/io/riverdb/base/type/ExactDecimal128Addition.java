package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Scale-aligned addition and subtraction over signed decimal128 values. */
final class ExactDecimal128Addition {
  private ExactDecimal128Addition() { }

  static StatusCode add(
      long leftHigh, long leftLow, int leftPrecision, int leftScale,
      long rightHigh, long rightLow, int rightPrecision, int rightScale,
      boolean subtract, int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    StatusCode status = ExactDecimal128Arithmetic.validateBinary(
        leftHigh, leftLow, leftPrecision, leftScale,
        rightHigh, rightLow, rightPrecision, rightScale,
        targetPrecision, targetScale, result, scratch);
    if (!status.isOk()) return status;
    int commonScale = Math.max(leftScale, rightScale);
    if (!scaleSigned(leftHigh, leftLow, commonScale - leftScale, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    long scaledLeftHigh = scratch.high;
    long scaledLeftLow = scratch.low;
    if (!scaleSigned(rightHigh, rightLow, commonScale - rightScale, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    if (subtract) negateScratch(scratch);
    if (!ExactDecimal128Math.addSigned(
        scaledLeftHigh, scaledLeftLow, scratch.high, scratch.low, scratch)
        || !ExactDecimal128.fits(
            scratch.high, scratch.low, ExactDecimal128.MAXIMUM_PRECISION)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    return ExactDecimal128Scale.apply(
        scratch.high, scratch.low, ExactDecimal128.MAXIMUM_PRECISION, commonScale,
        targetPrecision, targetScale, ExactDecimal128.ROUND_HALF_EVEN,
        false, result, scratch);
  }

  private static boolean scaleSigned(
      long high, long low, int exponent, ExactDecimal128.Scratch scratch) {
    boolean negative = high < 0;
    ExactDecimal128Math.magnitude(high, low, scratch);
    if (!ExactDecimal128Math.multiplyPower(scratch.high, scratch.low, exponent, scratch)
        || scratch.high < 0) return false;
    ExactDecimal128Math.signed(scratch.high, scratch.low, negative, scratch);
    return true;
  }

  private static void negateScratch(ExactDecimal128.Scratch scratch) {
    long negatedLow = ~scratch.low + 1;
    scratch.high = ~scratch.high + (negatedLow == 0 ? 1 : 0);
    scratch.low = negatedLow;
  }
}
