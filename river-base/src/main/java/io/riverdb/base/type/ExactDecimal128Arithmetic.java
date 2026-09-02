package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Shared admission and publication for wide exact-decimal arithmetic. */
public final class ExactDecimal128Arithmetic {
  private ExactDecimal128Arithmetic() { }

  public static StatusCode multiply(
      long leftHigh, long leftLow, int leftPrecision, int leftScale,
      long rightHigh, long rightLow, int rightPrecision, int rightScale,
      int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    return ExactDecimal128Product.multiply(
        leftHigh, leftLow, leftPrecision, leftScale,
        rightHigh, rightLow, rightPrecision, rightScale,
        targetPrecision, targetScale, result, scratch);
  }

  public static StatusCode divide(
      long leftHigh, long leftLow, int leftPrecision, int leftScale,
      long rightHigh, long rightLow, int rightPrecision, int rightScale,
      int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    return ExactDecimal128Division.divide(
        leftHigh, leftLow, leftPrecision, leftScale,
        rightHigh, rightLow, rightPrecision, rightScale,
        targetPrecision, targetScale, result, scratch);
  }

  /** Divides by an integral count without constructing a decimal divisor carrier. */
  public static StatusCode divideByLong(
      long high, long low, int sourcePrecision, int sourceScale,
      long divisor, int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    return ExactDecimal128Division.divideByLong(
        high, low, sourcePrecision, sourceScale, divisor,
        targetPrecision, targetScale, result, scratch);
  }

  public static StatusCode remainder(
      long leftHigh, long leftLow, int leftPrecision, int leftScale,
      long rightHigh, long rightLow, int rightPrecision, int rightScale,
      int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    return ExactDecimal128Remainder.remainder(
        leftHigh, leftLow, leftPrecision, leftScale,
        rightHigh, rightLow, rightPrecision, rightScale,
        targetPrecision, targetScale, result, scratch);
  }

  public static StatusCode floor(
      long high, long low, int sourcePrecision, int sourceScale,
      int targetPrecision, ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    return ExactDecimal128Integral.floor(
        high, low, sourcePrecision, sourceScale,
        targetPrecision, result, scratch);
  }

  public static StatusCode ceiling(
      long high, long low, int sourcePrecision, int sourceScale,
      int targetPrecision, ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    return ExactDecimal128Integral.ceiling(
        high, low, sourcePrecision, sourceScale,
        targetPrecision, result, scratch);
  }

  static StatusCode validateBinary(
      long leftHigh, long leftLow, int leftPrecision, int leftScale,
      long rightHigh, long rightLow, int rightPrecision, int rightScale,
      int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    return result != null && scratch != null
        && ExactDecimal128.validDescriptor(leftPrecision, leftScale)
        && ExactDecimal128.validDescriptor(rightPrecision, rightScale)
        && ExactDecimal128.validDescriptor(targetPrecision, targetScale)
        && ExactDecimal128.fits(leftHigh, leftLow, leftPrecision)
        && ExactDecimal128.fits(rightHigh, rightLow, rightPrecision)
        ? StatusCode.OK : StatusCode.DATATYPE_MISMATCH;
  }

  static boolean adjustScale(
      int scaleReduction, ExactDecimal128.Scratch scratch) {
    if (scaleReduction < 0) {
      if (!ExactDecimal128WidePower.multiplyValue(-scaleReduction, scratch)) return false;
      ExactDecimal128Wide.wideToQuotient(scratch);
      return true;
    }
    ExactDecimal128Wide.setDivisorOne(scratch);
    ExactDecimal128WidePower.multiplyDivisor(scaleReduction, scratch);
    ExactDecimal128WideDivide.divide(scratch);
    return ExactDecimal128WideRound.halfEven(scratch);
  }

  static StatusCode publish(
      boolean negative,
      int targetPrecision,
      ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    if (!ExactDecimal128Wide.quotientFits128(scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    ExactDecimal128Math.signed(scratch.q1, scratch.q0, negative, scratch);
    if (!ExactDecimal128.fits(scratch.high, scratch.low, targetPrecision)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.high = scratch.high;
    result.low = scratch.low;
    return StatusCode.OK;
  }
}
