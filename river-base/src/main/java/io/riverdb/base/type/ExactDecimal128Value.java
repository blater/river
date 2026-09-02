package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Bounds and sign operations over one signed decimal128 value. */
final class ExactDecimal128Value {
  private ExactDecimal128Value() { }

  static boolean fits(long high, long low, int precision) {
    if (precision < 1 || precision > ExactDecimal128.MAXIMUM_PRECISION) return false;
    long magnitudeLow = low;
    long magnitudeHigh = high;
    if (high < 0) {
      magnitudeLow = ~low + 1;
      magnitudeHigh = ~high + (magnitudeLow == 0 ? 1 : 0);
    }
    return ExactDecimal128Math.compareUnsigned(
        magnitudeHigh, magnitudeLow,
        ExactDecimal128Powers.high(precision),
        ExactDecimal128Powers.low(precision)) < 0;
  }

  static StatusCode negate(
      long high, long low, int precision, ExactDecimal128.Value result) {
    if (result == null || !fits(high, low, precision)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    long negatedLow = ~low + 1;
    long negatedHigh = ~high + (negatedLow == 0 ? 1 : 0);
    if (!fits(negatedHigh, negatedLow, precision)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.high = negatedHigh;
    result.low = negatedLow;
    return StatusCode.OK;
  }

  static StatusCode absolute(
      long high, long low, int precision, ExactDecimal128.Value result) {
    if (result == null || !fits(high, low, precision)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (high < 0) return negate(high, low, precision, result);
    result.high = high;
    result.low = low;
    return StatusCode.OK;
  }
}
