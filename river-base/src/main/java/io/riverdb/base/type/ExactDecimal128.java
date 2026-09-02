package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/**
 * Allocation-free exact decimal primitives over a signed two's-complement 128-bit unscaled value.
 * A caller owns each mutable value and scratch carrier; operations publish results only on success.
 */
public final class ExactDecimal128 {
  public static final int MAXIMUM_PRECISION = 38;
  public static final int ROUND_TRUNCATE = 0;
  public static final int ROUND_HALF_EVEN = 1;
  public static final int ROUND_HALF_AWAY = 2;

  private ExactDecimal128() { }

  public static boolean fits(long high, long low, int precision) {
    return ExactDecimal128Value.fits(high, low, precision);
  }

  /** Publishes a scale-zero integral value converted to the requested decimal shape. */
  public static StatusCode fromLong(
      long value,
      int precision,
      int scale,
      Value result,
      Scratch scratch) {
    if (!validDescriptor(precision, scale) || result == null || scratch == null) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return ExactDecimal128Scale.apply(
        value >> 63, value, 19, 0, precision, scale,
        ROUND_TRUNCATE, true, result, scratch);
  }

  public static StatusCode negate(
      long high, long low, int precision, Value result) {
    return ExactDecimal128Value.negate(high, low, precision, result);
  }

  public static StatusCode absolute(
      long high, long low, int precision, Value result) {
    return ExactDecimal128Value.absolute(high, low, precision, result);
  }

  public static StatusCode add(
      long leftHigh,
      long leftLow,
      int leftPrecision,
      int leftScale,
      long rightHigh,
      long rightLow,
      int rightPrecision,
      int rightScale,
      boolean subtract,
      int targetPrecision,
      int targetScale,
      Value result,
      Scratch scratch) {
    return ExactDecimal128Addition.add(
        leftHigh, leftLow, leftPrecision, leftScale,
        rightHigh, rightLow, rightPrecision, rightScale, subtract,
        targetPrecision, targetScale, result, scratch);
  }

  public static StatusCode quantize(
      long high,
      long low,
      int sourcePrecision,
      int sourceScale,
      int targetPrecision,
      int targetScale,
      int rounding,
      boolean requireExact,
      Value result,
      Scratch scratch) {
    return ExactDecimal128Scale.apply(
        high, low, sourcePrecision, sourceScale,
        targetPrecision, targetScale, rounding, requireExact, result, scratch);
  }

  public static int compare(
      long leftHigh,
      long leftLow,
      int leftScale,
      long rightHigh,
      long rightLow,
      int rightScale,
      Scratch scratch) {
    return ExactDecimal128Comparison.compare(
        leftHigh, leftLow, leftScale,
        rightHigh, rightLow, rightScale, scratch);
  }

  static boolean validDescriptor(int precision, int scale) {
    return precision >= 1 && precision <= MAXIMUM_PRECISION
        && scale >= 0 && scale <= precision;
  }

  static boolean validRounding(int rounding) {
    return rounding >= ROUND_TRUNCATE && rounding <= ROUND_HALF_AWAY;
  }

  /** Caller-owned result. Its fields change only when an operation succeeds. */
  public static final class Value {
    public long high;
    public long low;
  }

  /** Caller-owned work state; one instance may be reused by sequential operations. */
  public static final class Scratch {
    long high;
    long low;
    long remainderHigh;
    long remainderLow;
    long w3;
    long w2;
    long w1;
    long w0;
    long d3;
    long d2;
    long d1;
    long d0;
    long q3;
    long q2;
    long q1;
    long q0;
    long r3;
    long r2;
    long r1;
    long r0;
  }
}
