package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free multiplication with a 256-bit rescaling intermediate. */
final class ExactDecimal128Product {
  private ExactDecimal128Product() { }

  static StatusCode multiply(
      long leftHigh, long leftLow, int leftPrecision, int leftScale,
      long rightHigh, long rightLow, int rightPrecision, int rightScale,
      int targetPrecision, int targetScale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    StatusCode validation = ExactDecimal128Arithmetic.validateBinary(
        leftHigh, leftLow, leftPrecision, leftScale,
        rightHigh, rightLow, rightPrecision, rightScale,
        targetPrecision, targetScale, result, scratch);
    if (validation != StatusCode.OK) return validation;
    boolean negative = (leftHigh ^ rightHigh) < 0;
    ExactDecimal128Math.magnitude(leftHigh, leftLow, scratch);
    long magnitudeHigh = scratch.high;
    long magnitudeLow = scratch.low;
    ExactDecimal128Math.magnitude(rightHigh, rightLow, scratch);
    ExactDecimal128WideProduct.multiply(
        magnitudeHigh, magnitudeLow, scratch.high, scratch.low, scratch);
    if (!ExactDecimal128Arithmetic.adjustScale(
        leftScale + rightScale - targetScale, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    return ExactDecimal128Arithmetic.publish(
        negative, targetPrecision, result, scratch);
  }
}
