package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free conversion between binary64 and signed decimal128 values. */
public final class ExactDecimal128Conversion {
  private ExactDecimal128Conversion() { }

  /** Returns the correctly rounded binary64 value of a valid decimal128 value. */
  public static double toDouble(
      long high, long low, int scale, ExactDecimal128.Scratch scratch) {
    return ExactDecimal128ToDouble.convert(high, low, scale, scratch);
  }

  public static StatusCode fromDouble(
      double value,
      int precision,
      int scale,
      ExactDecimal128.Value result,
      ExactDecimal128.Scratch scratch) {
    return ExactDecimal128FromDouble.convert(
        value, precision, scale, result, scratch);
  }
}
