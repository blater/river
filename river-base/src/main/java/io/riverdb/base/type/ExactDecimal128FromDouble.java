package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Exact binary64-to-scaled-decimal conversion with half-even rounding. */
final class ExactDecimal128FromDouble {
  private ExactDecimal128FromDouble() { }

  static StatusCode convert(
      double value, int precision, int scale,
      ExactDecimal128.Value result, ExactDecimal128.Scratch scratch) {
    if (result == null || scratch == null
        || precision < 1 || precision > ExactDecimal128.MAXIMUM_PRECISION
        || scale < 0 || scale > precision) return StatusCode.DATATYPE_MISMATCH;
    long bits = Double.doubleToRawLongBits(value);
    int encodedExponent = (int) (bits >>> 52 & 0x7ff);
    if (encodedExponent == 0x7ff) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    long significand = bits & ((1L << 52) - 1);
    if (encodedExponent != 0) significand |= 1L << 52;
    if (significand == 0) return publish(0, 0, precision, result);
    ExactDecimal128Wide.set128(0, significand, scratch);
    if (!ExactDecimal128WidePower.multiplyValue(scale, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    int binaryShift = encodedExponent == 0
        ? -1074 : encodedExponent - 1023 - 52;
    StatusCode status = binaryShift >= 0
        ? scaleUp(binaryShift, scratch) : scaleDown(-binaryShift, scratch);
    if (!status.isOk()) return status;
    if (scratch.q1 < 0) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    long high = scratch.q1;
    long low = scratch.q0;
    if ((bits & Long.MIN_VALUE) != 0 && (high != 0 || low != 0)) {
      low = ~low + 1;
      high = ~high + (low == 0 ? 1 : 0);
    }
    return publish(high, low, precision, result);
  }

  private static StatusCode scaleUp(
      int shift, ExactDecimal128.Scratch scratch) {
    if (!ExactDecimal128ConversionWide.shiftValue(shift, scratch)
        || scratch.w3 != 0 || scratch.w2 != 0) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    scratch.q3 = 0;
    scratch.q2 = 0;
    scratch.q1 = scratch.w1;
    scratch.q0 = scratch.w0;
    return StatusCode.OK;
  }

  private static StatusCode scaleDown(
      int reduction, ExactDecimal128.Scratch scratch) {
    if (reduction >= 256) {
      scratch.q3 = 0;
      scratch.q2 = 0;
      scratch.q1 = 0;
      scratch.q0 = 0;
      return StatusCode.OK;
    }
    ExactDecimal128Wide.setDivisorOne(scratch);
    ExactDecimal128ConversionWide.shiftDivisor(reduction, scratch);
    ExactDecimal128WideDivide.divide(scratch);
    int half = ExactDecimal128ConversionWide.compareTwiceRemainder(scratch);
    if (half > 0 || half == 0 && (scratch.q0 & 1) != 0) {
      ExactDecimal128ConversionWide.incrementQuotient(scratch);
    }
    return scratch.q3 == 0 && scratch.q2 == 0
        ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
  }

  private static StatusCode publish(
      long high, long low, int precision, ExactDecimal128.Value result) {
    if (!ExactDecimal128.fits(high, low, precision)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.high = high;
    result.low = low;
    return StatusCode.OK;
  }
}
