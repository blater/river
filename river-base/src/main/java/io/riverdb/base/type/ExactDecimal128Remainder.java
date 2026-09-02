package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free signed decimal remainder. */
final class ExactDecimal128Remainder {
  private ExactDecimal128Remainder() { }

  static StatusCode remainder(
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
    int commonScale = Math.max(leftScale, rightScale);
    ExactDecimal128Wide.setDivisor128(scratch.high, scratch.low, scratch);
    if (!ExactDecimal128WidePower.multiplyDivisor(
        commonScale - rightScale, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    ExactDecimal128Math.magnitude(leftHigh, leftLow, scratch);
    ExactDecimal128Wide.set128(scratch.high, scratch.low, scratch);
    if (!ExactDecimal128WidePower.multiplyValue(commonScale - leftScale, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    ExactDecimal128WideDivide.divide(scratch);
    ExactDecimal128Wide.remainderToWide(scratch);
    if (!ExactDecimal128Arithmetic.adjustScale(commonScale - targetScale, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    return ExactDecimal128Arithmetic.publish(
        leftHigh < 0, targetPrecision, result, scratch);
  }
}
