package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Scale conversion for signed 128-bit decimal values. */
final class ExactDecimal128Scale {
  private ExactDecimal128Scale() { }

  static StatusCode apply(
      long high,
      long low,
      int sourcePrecision,
      int sourceScale,
      int targetPrecision,
      int targetScale,
      int rounding,
      boolean requireExact,
      ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    if (!valid(
        high, low, sourcePrecision, sourceScale,
        targetPrecision, targetScale, rounding, result, scratch)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (targetScale >= sourceScale) {
      return widen(
          high, low, targetPrecision, targetScale - sourceScale, result, scratch);
    }
    boolean negative = high < 0;
    ExactDecimal128Math.magnitude(high, low, scratch);
    long divisorHigh = ExactDecimal128Powers.high(sourceScale - targetScale);
    long divisorLow = ExactDecimal128Powers.low(sourceScale - targetScale);
    ExactDecimal128Math.divideUnsigned(
        scratch.high, scratch.low, divisorHigh, divisorLow, scratch);
    if (requireExact && (scratch.remainderHigh != 0 || scratch.remainderLow != 0)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    if (shouldRound(divisorHigh, divisorLow, rounding, scratch)
        && !ExactDecimal128Math.incrementUnsigned(scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    ExactDecimal128Math.signed(scratch.high, scratch.low, negative, scratch);
    if (!ExactDecimal128.fits(scratch.high, scratch.low, targetPrecision)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.high = scratch.high;
    result.low = scratch.low;
    return StatusCode.OK;
  }

  private static StatusCode widen(
      long high,
      long low,
      int targetPrecision,
      int exponent,
      ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    boolean negative = high < 0;
    ExactDecimal128Math.magnitude(high, low, scratch);
    if (!ExactDecimal128Math.multiplyPower(
        scratch.high, scratch.low, exponent, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    ExactDecimal128Math.signed(scratch.high, scratch.low, negative, scratch);
    if (!ExactDecimal128.fits(scratch.high, scratch.low, targetPrecision)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.high = scratch.high;
    result.low = scratch.low;
    return StatusCode.OK;
  }

  private static boolean shouldRound(
      long divisorHigh,
      long divisorLow,
      int rounding,
      ExactDecimal128.Scratch scratch) {
    if (rounding == ExactDecimal128.ROUND_TRUNCATE
        || scratch.remainderHigh == 0 && scratch.remainderLow == 0) {
      return false;
    }
    long doubledHigh = scratch.remainderHigh << 1 | scratch.remainderLow >>> 63;
    long doubledLow = scratch.remainderLow << 1;
    int half = ExactDecimal128Math.compareUnsigned(
        doubledHigh, doubledLow, divisorHigh, divisorLow);
    return half > 0 || half == 0
        && (rounding == ExactDecimal128.ROUND_HALF_AWAY || (scratch.low & 1) != 0);
  }

  private static boolean valid(
      long high,
      long low,
      int sourcePrecision,
      int sourceScale,
      int targetPrecision,
      int targetScale,
      int rounding,
      ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    return result != null && scratch != null
        && ExactDecimal128.validDescriptor(sourcePrecision, sourceScale)
        && ExactDecimal128.validDescriptor(targetPrecision, targetScale)
        && ExactDecimal128.validRounding(rounding)
        && ExactDecimal128.fits(high, low, sourcePrecision);
  }
}
