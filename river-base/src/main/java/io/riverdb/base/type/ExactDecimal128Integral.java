package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free floor and ceiling conversion to a scale-zero decimal. */
final class ExactDecimal128Integral {
  private ExactDecimal128Integral() { }

  static StatusCode floor(
      long high, long low, int sourcePrecision, int sourceScale,
      int targetPrecision, ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    return integral(
        high, low, sourcePrecision, sourceScale,
        targetPrecision, true, result, scratch);
  }

  static StatusCode ceiling(
      long high, long low, int sourcePrecision, int sourceScale,
      int targetPrecision, ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    return integral(
        high, low, sourcePrecision, sourceScale,
        targetPrecision, false, result, scratch);
  }

  private static StatusCode integral(
      long high, long low, int sourcePrecision, int sourceScale,
      int targetPrecision, boolean floor, ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    if (result == null || scratch == null
        || !ExactDecimal128.validDescriptor(sourcePrecision, sourceScale)
        || !ExactDecimal128.validDescriptor(targetPrecision, 0)
        || !ExactDecimal128.fits(high, low, sourcePrecision)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    boolean negative = high < 0;
    ExactDecimal128Math.magnitude(high, low, scratch);
    ExactDecimal128Math.divideUnsigned(
        scratch.high, scratch.low,
        ExactDecimal128Powers.high(sourceScale),
        ExactDecimal128Powers.low(sourceScale), scratch);
    boolean fractional = scratch.remainderHigh != 0 || scratch.remainderLow != 0;
    if (fractional && floor == negative
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
}
