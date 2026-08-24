package io.riverdb.base.type;

import static io.riverdb.base.type.ExactDecimalDescriptors.exactNumeric;
import static io.riverdb.base.type.ExactDecimalDescriptors.scale;
import static io.riverdb.base.type.ExactDecimalDescriptors.validOperation;
import static io.riverdb.base.type.ExactDecimalDescriptors.valueFitsDescriptor;

import io.riverdb.base.error.StatusCode;

/** Allocation-free operations over River's signed scaled-long decimal representation. */
public final class ExactDecimal {
  private static final long[] POWERS_OF_TEN = {
      1L,
      10L,
      100L,
      1_000L,
      10_000L,
      100_000L,
      1_000_000L,
      10_000_000L,
      100_000_000L,
      1_000_000_000L,
      10_000_000_000L,
      100_000_000_000L,
      1_000_000_000_000L,
      10_000_000_000_000L,
      100_000_000_000_000L,
      1_000_000_000_000_000L,
      10_000_000_000_000_000L,
      100_000_000_000_000_000L,
      1_000_000_000_000_000_000L
  };

  private ExactDecimal() {
  }

  public static long powerOfTen(int exponent) {
    return exponent >= 0 && exponent < POWERS_OF_TEN.length
        ? POWERS_OF_TEN[exponent] : 0;
  }

  public static boolean fits(long unscaled, int precision) {
    return precision >= 1
        && precision <= SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION
        && unscaled > -POWERS_OF_TEN[precision]
        && unscaled < POWERS_OF_TEN[precision];
  }

  public static int addResultDescriptor(int left, int right) {
    return ExactDecimalDescriptors.binaryResult(left, right, 0);
  }

  public static int multiplyResultDescriptor(int left, int right) {
    return ExactDecimalDescriptors.binaryResult(left, right, 1);
  }

  public static int divideResultDescriptor(int left, int right) {
    return ExactDecimalDescriptors.binaryResult(left, right, 2);
  }

  public static int remainderResultDescriptor(int left, int right) {
    return ExactDecimalDescriptors.binaryResult(left, right, 3);
  }

  public static int quantizedDescriptor(int source, int targetScale) {
    return ExactDecimalDescriptors.quantized(source, targetScale);
  }

  public static StatusCode negate(
      long value, int descriptor, LongValue result) {
    if (result == null || !exactNumeric(descriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (value == Long.MIN_VALUE) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    long negated = -value;
    if (!valueFitsDescriptor(negated, descriptor)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = negated;
    return StatusCode.OK;
  }

  public static StatusCode absolute(
      long value, int descriptor, LongValue result) {
    if (result == null || !exactNumeric(descriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (value == Long.MIN_VALUE) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = Math.abs(value);
    return StatusCode.OK;
  }

  public static StatusCode add(
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor,
      boolean subtract,
      int targetDescriptor,
      LongValue result,
      WideScratch scratch) {
    if (!validOperation(
        leftDescriptor, rightDescriptor, targetDescriptor, result, scratch)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (targetDescriptor == SqlTypeDescriptor.BIGINT) {
      return addSigned(left, right, subtract, result)
          ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    int commonScale = Math.max(scale(leftDescriptor), scale(rightDescriptor));
    signedScaled(left, commonScale - scale(leftDescriptor), scratch);
    long leftHigh = scratch.high;
    long leftLow = scratch.low;
    signedScaled(right, commonScale - scale(rightDescriptor), scratch);
    if (subtract) {
      negatePair(scratch);
    }
    addPair(leftHigh, leftLow, scratch.high, scratch.low, scratch);
    return finishPair(
        scratch.high,
        scratch.low,
        commonScale - scale(targetDescriptor),
        true,
        targetDescriptor,
        result,
        scratch);
  }

  public static StatusCode multiply(
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor,
      int targetDescriptor,
      LongValue result,
      WideScratch scratch) {
    if (!validOperation(
        leftDescriptor, rightDescriptor, targetDescriptor, result, scratch)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (targetDescriptor == SqlTypeDescriptor.BIGINT) {
      return multiplySigned(left, right, result)
          ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    long high = Math.multiplyHigh(left, right);
    long low = left * right;
    return finishPair(
        high,
        low,
        scale(leftDescriptor) + scale(rightDescriptor) - scale(targetDescriptor),
        true,
        targetDescriptor,
        result,
        scratch);
  }

  public static StatusCode divide(
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor,
      int targetDescriptor,
      LongValue result,
      WideScratch scratch) {
    if (!validOperation(
        leftDescriptor, rightDescriptor, targetDescriptor, result, scratch)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (right == 0) {
      return StatusCode.DIVISION_BY_ZERO;
    }
    if (targetDescriptor == SqlTypeDescriptor.BIGINT) {
      if (left == Long.MIN_VALUE && right == -1) {
        return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
      result.value = left / right;
      return StatusCode.OK;
    }
    boolean negative = (left ^ right) < 0;
    long numerator = unsignedMagnitude(left);
    long denominator = unsignedMagnitude(right);
    int exponent = scale(rightDescriptor) + scale(targetDescriptor)
        - scale(leftDescriptor);
    if (!scaledQuotient(
        numerator, denominator, exponent, negative, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    long value = scratch.quotient;
    if (!valueFitsDescriptor(value, targetDescriptor)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = value;
    return StatusCode.OK;
  }

  public static StatusCode remainder(
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor,
      int targetDescriptor,
      LongValue result,
      WideScratch scratch) {
    if (!validOperation(
        leftDescriptor, rightDescriptor, targetDescriptor, result, scratch)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    if (right == 0) {
      return StatusCode.DIVISION_BY_ZERO;
    }
    if (targetDescriptor == SqlTypeDescriptor.BIGINT) {
      result.value = left == Long.MIN_VALUE && right == -1 ? 0 : left % right;
      return StatusCode.OK;
    }
    int commonScale = Math.max(scale(leftDescriptor), scale(rightDescriptor));
    unsignedScaled(unsignedMagnitude(left), commonScale - scale(leftDescriptor), scratch);
    long leftHigh = scratch.high;
    long leftLow = scratch.low;
    unsignedScaled(unsignedMagnitude(right), commonScale - scale(rightDescriptor), scratch);
    unsignedRemainder(
        leftHigh, leftLow, scratch.high, scratch.low, scratch);
    if (left < 0 && (scratch.high != 0 || scratch.low != 0)) {
      negatePair(scratch);
    }
    return finishPair(
        scratch.high,
        scratch.low,
        commonScale - scale(targetDescriptor),
        true,
        targetDescriptor,
        result,
        scratch);
  }

  public static StatusCode quantize(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      boolean halfEven,
      boolean requireExact,
      LongValue result,
      WideScratch scratch) {
    return ExactDecimalQuantize.apply(
        value,
        sourceDescriptor,
        targetDescriptor,
        halfEven,
        requireExact,
        result,
        scratch);
  }

  /** Converts an ordered lower or exclusive-upper bound to the least target-scale value. */
  public static boolean ceilingScale(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      LongValue result) {
    if (result == null || !exactNumeric(sourceDescriptor)
        || !exactNumeric(targetDescriptor)) {
      return false;
    }
    int sourceScale = scale(sourceDescriptor);
    int targetScale = scale(targetDescriptor);
    if (targetScale >= sourceScale) {
      if (SqlTypeDescriptor.typeId(targetDescriptor)
          == SqlTypeDescriptor.TYPE_ID_BIGINT) {
        result.value = value;
        return sourceScale == 0;
      }
      return widenScale(value, sourceDescriptor, targetDescriptor, result);
    }
    long divisor = POWERS_OF_TEN[sourceScale - targetScale];
    long converted = value / divisor;
    if (value % divisor > 0) {
      converted++;
    }
    if (!valueFitsDescriptor(converted, targetDescriptor)) {
      return false;
    }
    result.value = converted;
    return true;
  }

  public static StatusCode integral(
      long value,
      int sourceDescriptor,
      boolean ceiling,
      LongValue result) {
    if (result == null || !exactNumeric(sourceDescriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int sourceScale = scale(sourceDescriptor);
    if (sourceScale == 0) {
      result.value = value;
      return StatusCode.OK;
    }
    long divisor = POWERS_OF_TEN[sourceScale];
    long integral = value / divisor;
    long remainder = value % divisor;
    if (remainder != 0 && (ceiling ? value > 0 : value < 0)) {
      integral += ceiling ? 1 : -1;
    }
    if (!fits(integral, SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = integral;
    return StatusCode.OK;
  }

  /**
   * Converts an exact numeric value to a wider scale. Callers must first establish that the
   * target descriptor admits the source descriptor. The result carrier is changed only on
   * success.
   */
  public static boolean widenScale(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      LongValue result) {
    if (result == null
        || !SqlTypeDescriptor.isValid(sourceDescriptor)
        || SqlTypeDescriptor.typeId(targetDescriptor) != SqlTypeDescriptor.TYPE_ID_DECIMAL
        || !SqlTypeDescriptor.canImplicitlyCast(sourceDescriptor, targetDescriptor)) {
      return false;
    }
    int sourceScale = scale(sourceDescriptor);
    int targetScale = SqlTypeDescriptor.parameterTwo(targetDescriptor);
    long factor = POWERS_OF_TEN[targetScale - sourceScale];
    long converted = value * factor;
    if (Math.multiplyHigh(value, factor) != converted >> 63) {
      return false;
    }
    if (!fits(converted, SqlTypeDescriptor.parameterOne(targetDescriptor))) {
      return false;
    }
    result.value = converted;
    return true;
  }

  public static int compare(
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor) {
    int leftScale = scale(leftDescriptor);
    int rightScale = scale(rightDescriptor);
    if (leftScale == rightScale) {
      return Long.compare(left, right);
    }
    if (leftScale > rightScale) {
      return -compareDifferentScale(right, rightScale, left, leftScale);
    }
    return compareDifferentScale(left, leftScale, right, rightScale);
  }

  public static boolean average(
      long sumHigh,
      long sumLow,
      long count,
      int inputScale,
      int targetDescriptor,
      LongValue result,
      WideScratch scratch) {
    return ExactDecimalAverage.compute(
        sumHigh,
        sumLow,
        count,
        inputScale,
        targetDescriptor,
        result,
        scratch);
  }

  private static int compareDifferentScale(
      long lowerScaleValue,
      int lowerScale,
      long higherScaleValue,
      int higherScale) {
    long factor = POWERS_OF_TEN[higherScale - lowerScale];
    long quotient = higherScaleValue / factor;
    int comparison = Long.compare(lowerScaleValue, quotient);
    return comparison != 0
        ? comparison : Long.compare(0, higherScaleValue % factor);
  }

  static void signedScaled(long value, int exponent, WideScratch result) {
    long factor = POWERS_OF_TEN[exponent];
    result.high = Math.multiplyHigh(value, factor);
    result.low = value * factor;
  }

  private static void unsignedScaled(long value, int exponent, WideScratch result) {
    long factor = POWERS_OF_TEN[exponent];
    result.low = value * factor;
    result.high = unsignedMultiplyHigh(value, factor);
  }

  private static long unsignedMultiplyHigh(long left, long right) {
    return Math.multiplyHigh(left, right)
        + (left < 0 ? right : 0)
        + (right < 0 ? left : 0);
  }

  private static void addPair(
      long leftHigh,
      long leftLow,
      long rightHigh,
      long rightLow,
      WideScratch result) {
    long low = leftLow + rightLow;
    result.high = leftHigh + rightHigh
        + (Long.compareUnsigned(low, leftLow) < 0 ? 1 : 0);
    result.low = low;
  }

  private static void negatePair(WideScratch value) {
    value.low = ~value.low + 1;
    value.high = ~value.high + (value.low == 0 ? 1 : 0);
  }

  static StatusCode finishPair(
      long high,
      long low,
      int scaleReduction,
      boolean halfEven,
      int descriptor,
      LongValue result,
      WideScratch scratch) {
    if (scaleReduction < 0 || scaleReduction >= POWERS_OF_TEN.length) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    if (!reducePair(high, low, scaleReduction, halfEven, scratch)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    long value = scratch.quotient;
    if (!valueFitsDescriptor(value, descriptor)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = value;
    return StatusCode.OK;
  }

  private static boolean reducePair(
      long high,
      long low,
      int scaleReduction,
      boolean halfEven,
      WideScratch scratch) {
    if (scaleReduction == 0) {
      scratch.quotient = low;
      return high == low >> 63;
    }
    long divisor = POWERS_OF_TEN[scaleReduction];
    return divideSigned(high, low, divisor, scratch)
        && roundReducedPair(divisor, halfEven, scratch);
  }

  private static boolean roundReducedPair(
      long divisor, boolean halfEven, WideScratch scratch) {
    if (!halfEven || scratch.remainder == 0
        || !shouldRound(scratch.remainder, divisor, scratch.quotient)) {
      return true;
    }
    if (scratch.negative && scratch.quotient == Long.MIN_VALUE
        || !scratch.negative && scratch.quotient == Long.MAX_VALUE) {
      return false;
    }
    scratch.quotient += scratch.negative ? -1 : 1;
    return true;
  }

  static boolean shouldRound(long magnitudeRemainder, long divisor, long whole) {
    long complement = divisor - magnitudeRemainder;
    return magnitudeRemainder > complement
        || magnitudeRemainder == complement && (whole & 1) != 0;
  }

  private static long unsignedMagnitude(long value) {
    return value < 0 ? ~value + 1 : value;
  }

  private static boolean addSigned(
      long left, long right, boolean subtract, LongValue result) {
    long value = subtract ? left - right : left + right;
    boolean overflow = subtract
        ? ((left ^ right) & (left ^ value)) < 0
        : ((left ^ value) & (right ^ value)) < 0;
    if (overflow) {
      return false;
    }
    result.value = value;
    return true;
  }

  private static boolean multiplySigned(long left, long right, LongValue result) {
    long value = left * right;
    if (Math.multiplyHigh(left, right) != value >> 63) {
      return false;
    }
    result.value = value;
    return true;
  }

  private static boolean scaledQuotient(
      long numerator,
      long denominator,
      int exponent,
      boolean negative,
      WideScratch scratch) {
    if (exponent < 0) {
      return quotientWithScaledDenominator(
          numerator, denominator, -exponent, negative, scratch);
    }
    long quotient = Long.divideUnsigned(numerator, denominator);
    long remainder = Long.remainderUnsigned(numerator, denominator);
    if (Long.compareUnsigned(quotient, Long.MAX_VALUE) > 0) {
      return false;
    }
    for (int index = 0; index < exponent; index++) {
      if (quotient > 100_000_000_000_000_000L) {
        return false;
      }
      unsignedScaled(remainder, 1, scratch);
      if (!divideUnsigned(scratch.high, scratch.low, denominator, scratch)) {
        return false;
      }
      quotient = quotient * 10 + scratch.quotient;
      remainder = scratch.remainder;
    }
    if (remainder != 0 && shouldRound(remainder, denominator, quotient)) {
      if (quotient == Long.MAX_VALUE) {
        return false;
      }
      quotient++;
    }
    scratch.quotient = negative ? -quotient : quotient;
    scratch.negative = negative;
    return true;
  }

  private static boolean quotientWithScaledDenominator(
      long numerator,
      long denominator,
      int exponent,
      boolean negative,
      WideScratch scratch) {
    unsignedScaled(denominator, exponent, scratch);
    long divisorHigh = scratch.high;
    long divisorLow = scratch.low;
    long quotient = 0;
    long remainder = numerator;
    if (divisorHigh == 0
        && Long.compareUnsigned(numerator, divisorLow) >= 0) {
      quotient = Long.divideUnsigned(numerator, divisorLow);
      remainder = Long.remainderUnsigned(numerator, divisorLow);
    }
    long doubledLow = remainder << 1;
    long doubledHigh = remainder >>> 63;
    int halfComparison = compareUnsigned(
        doubledHigh, doubledLow, divisorHigh, divisorLow);
    if (halfComparison > 0 || halfComparison == 0 && (quotient & 1) != 0) {
      quotient++;
    }
    if (Long.compareUnsigned(quotient, Long.MAX_VALUE) > 0) {
      return false;
    }
    scratch.quotient = negative ? -quotient : quotient;
    scratch.negative = negative;
    return true;
  }

  private static void unsignedRemainder(
      long dividendHigh,
      long dividendLow,
      long divisorHigh,
      long divisorLow,
      WideScratch result) {
    long remainderHigh = 0;
    long remainderLow = 0;
    for (int bit = 127; bit >= 0; bit--) {
      remainderHigh = remainderHigh << 1 | remainderLow >>> 63;
      remainderLow = remainderLow << 1
          | (bit >= 64 ? dividendHigh >>> bit - 64 : dividendLow >>> bit) & 1;
      if (compareUnsigned(
          remainderHigh, remainderLow, divisorHigh, divisorLow) >= 0) {
        long nextLow = remainderLow - divisorLow;
        remainderHigh = remainderHigh - divisorHigh
            - (Long.compareUnsigned(remainderLow, divisorLow) < 0 ? 1 : 0);
        remainderLow = nextLow;
      }
    }
    result.high = remainderHigh;
    result.low = remainderLow;
  }

  private static int compareUnsigned(
      long leftHigh, long leftLow, long rightHigh, long rightLow) {
    int high = Long.compareUnsigned(leftHigh, rightHigh);
    return high != 0 ? high : Long.compareUnsigned(leftLow, rightLow);
  }

  static boolean divideSigned(
      long high, long low, long divisor, WideScratch result) {
    boolean negative = high < 0;
    long magnitudeHigh = high;
    long magnitudeLow = low;
    if (negative) {
      magnitudeLow = ~low + 1;
      magnitudeHigh = ~high + (magnitudeLow == 0 ? 1 : 0);
    }
    if (!divideUnsigned(magnitudeHigh, magnitudeLow, divisor, result)) {
      return false;
    }
    long quotient = result.quotient;
    if ((!negative && quotient < 0)
        || (negative
            && Long.compareUnsigned(quotient, Long.MIN_VALUE) > 0)) {
      return false;
    }
    result.quotient = negative ? -quotient : quotient;
    result.negative = negative;
    return true;
  }

  static boolean divideUnsigned(
      long high, long low, long divisor, WideScratch result) {
    long quotient = 0;
    long remainder = 0;
    for (int bit = 127; bit >= 0; bit--) {
      long inputBit = bit >= 64
          ? high >>> bit - 64 & 1 : low >>> bit & 1;
      boolean carry = remainder < 0;
      remainder = remainder << 1 | inputBit;
      if (carry || Long.compareUnsigned(remainder, divisor) >= 0) {
        remainder -= divisor;
        if (bit >= 64) {
          return false;
        }
        quotient |= 1L << bit;
      }
    }
    result.quotient = quotient;
    result.remainder = remainder;
    return true;
  }

  /** Caller-owned primitive result used where conversion failure must not mutate output. */
  public static final class LongValue {
    public long value;
  }

  /** Caller-owned scratch for signed-wide decimal division. */
  public static final class WideScratch {
    long high;
    long low;
    long quotient;
    long remainder;
    boolean negative;
  }
}
