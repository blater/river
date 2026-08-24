package io.riverdb.base.type;

import io.riverdb.base.type.ExactDecimal.LongValue;
import io.riverdb.base.type.ExactDecimal.WideScratch;

/** Signed-wide average with half-even rounding. */
final class ExactDecimalAverage {
  private ExactDecimalAverage() { }

  static boolean compute(
      long sumHigh,
      long sumLow,
      long count,
      int inputScale,
      int targetDescriptor,
      LongValue result,
      WideScratch scratch) {
    if (count <= 0
        || result == null
        || scratch == null
        || SqlTypeDescriptor.typeId(targetDescriptor)
            != SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      return false;
    }
    int targetScale = SqlTypeDescriptor.parameterTwo(targetDescriptor);
    if (targetScale < inputScale
        || !ExactDecimal.divideSigned(sumHigh, sumLow, count, scratch)) {
      return false;
    }
    long factor = ExactDecimal.powerOfTen(targetScale - inputScale);
    long whole = scratch.quotient * factor;
    if (Math.multiplyHigh(scratch.quotient, factor) != whole >> 63) {
      return false;
    }
    long remainderLow = scratch.remainder * factor;
    long remainderHigh = Math.multiplyHigh(scratch.remainder, factor);
    boolean negative = scratch.negative;
    if (!ExactDecimal.divideUnsigned(remainderHigh, remainderLow, count, scratch)) {
      return false;
    }
    long fraction = scratch.quotient;
    boolean aboveHalf = scratch.remainder > count - scratch.remainder;
    boolean tie = scratch.remainder == count - scratch.remainder;
    if (aboveHalf || tie && ((whole ^ fraction) & 1) != 0) {
      fraction++;
    }
    long value = negative ? whole - fraction : whole + fraction;
    if (negative
        ? ((whole ^ fraction) & (whole ^ value)) < 0
        : ((whole ^ value) & (fraction ^ value)) < 0) {
      return false;
    }
    if (!ExactDecimal.fits(value, SqlTypeDescriptor.parameterOne(targetDescriptor))) {
      return false;
    }
    result.value = value;
    return true;
  }
}
