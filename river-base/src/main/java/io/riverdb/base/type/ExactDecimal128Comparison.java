package io.riverdb.base.type;

/** Cross-scale signed comparison over decimal128 values. */
final class ExactDecimal128Comparison {
  private ExactDecimal128Comparison() { }

  static int compare(
      long leftHigh, long leftLow, int leftScale,
      long rightHigh, long rightLow, int rightScale,
      ExactDecimal128.Scratch scratch) {
    if (leftScale == rightScale) {
      return ExactDecimal128Math.compareSigned(
          leftHigh, leftLow, rightHigh, rightLow);
    }
    boolean leftNegative = leftHigh < 0;
    boolean rightNegative = rightHigh < 0;
    if (leftNegative != rightNegative) return leftNegative ? -1 : 1;
    ExactDecimal128Math.magnitude(leftHigh, leftLow, scratch);
    long leftMagnitudeHigh = scratch.high;
    long leftMagnitudeLow = scratch.low;
    ExactDecimal128Math.magnitude(rightHigh, rightLow, scratch);
    int magnitude = leftScale < rightScale
        ? compareDifferentScale(
            leftMagnitudeHigh, leftMagnitudeLow, leftScale,
            scratch.high, scratch.low, rightScale, scratch)
        : -compareDifferentScale(
            scratch.high, scratch.low, rightScale,
            leftMagnitudeHigh, leftMagnitudeLow, leftScale, scratch);
    return leftNegative ? -magnitude : magnitude;
  }

  private static int compareDifferentScale(
      long lowerHigh, long lowerLow, int lowerScale,
      long higherHigh, long higherLow, int higherScale,
      ExactDecimal128.Scratch scratch) {
    int exponent = higherScale - lowerScale;
    ExactDecimal128Math.divideUnsigned(
        higherHigh, higherLow,
        ExactDecimal128Powers.high(exponent),
        ExactDecimal128Powers.low(exponent), scratch);
    int whole = ExactDecimal128Math.compareUnsigned(
        lowerHigh, lowerLow, scratch.high, scratch.low);
    if (whole != 0) return whole;
    return scratch.remainderHigh == 0 && scratch.remainderLow == 0 ? 0 : -1;
  }
}
