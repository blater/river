package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free decimal division and aggregate-count division. */
final class ExactDecimal128Division {
  private ExactDecimal128Division() { }

  static StatusCode divide(
      long leftHigh, long leftLow, int leftPrecision, int leftScale,
      long rightHigh, long rightLow, int rightPrecision, int rightScale,
      int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    StatusCode validation = ExactDecimal128Arithmetic.validateBinary(
        leftHigh, leftLow, leftPrecision, leftScale,
        rightHigh, rightLow, rightPrecision, rightScale,
        targetPrecision, targetScale, result, scratch);
    if (validation != StatusCode.OK) return validation;
    ExactDecimal128Math.magnitude(rightHigh, rightLow, scratch);
    if ((scratch.high | scratch.low) == 0) return StatusCode.DIVISION_BY_ZERO;
    long divisorHigh = scratch.high;
    long divisorLow = scratch.low;
    boolean negative = (leftHigh ^ rightHigh) < 0;
    ExactDecimal128Math.magnitude(leftHigh, leftLow, scratch);
    ExactDecimal128Wide.set128(scratch.high, scratch.low, scratch);
    ExactDecimal128Wide.setDivisor128(divisorHigh, divisorLow, scratch);
    int expansion = targetScale + rightScale - leftScale;
    boolean scaled = expansion >= 0
        ? ExactDecimal128WidePower.multiplyValue(expansion, scratch)
        : ExactDecimal128WidePower.multiplyDivisor(-expansion, scratch);
    if (!scaled) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    ExactDecimal128WideDivide.divide(scratch);
    if (!ExactDecimal128WideRound.halfEven(scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    return ExactDecimal128Arithmetic.publish(
        negative, targetPrecision, result, scratch);
  }

  static StatusCode divideByLong(
      long high, long low, int sourcePrecision, int sourceScale,
      long divisor, int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    return divide(
        high, low, sourcePrecision, sourceScale,
        divisor >> 63, divisor, 19, 0,
        targetPrecision, targetScale, result, scratch);
  }
}
