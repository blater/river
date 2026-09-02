package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal.LongValue;
import io.riverdb.base.type.ExactDecimal.WideScratch;

/** Scale reduction and half-even rounding for exact decimals. */
final class ExactDecimalQuantize {
  private ExactDecimalQuantize() { }

  static StatusCode apply(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      boolean halfEven,
      boolean requireExact,
      LongValue result,
      WideScratch scratch) {
    if (result == null || scratch == null
        || !ExactDecimalDescriptors.exactNumeric(sourceDescriptor)
        || !ExactDecimalDescriptors.exactNumeric(targetDescriptor)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int sourceScale = ExactDecimalDescriptors.scale(sourceDescriptor);
    int targetScale = ExactDecimalDescriptors.scale(targetDescriptor);
    if (targetScale >= sourceScale) {
      ExactDecimal.signedScaled(value, targetScale - sourceScale, scratch);
      return ExactDecimal.finishPair(
          scratch.high, scratch.low, 0, false, targetDescriptor, result, scratch);
    }
    long divisor = ExactDecimal.powerOfTen(sourceScale - targetScale);
    long remainder = value % divisor;
    if (requireExact && remainder != 0) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    long converted = value / divisor;
    long magnitude = Math.abs(remainder);
    boolean round = remainder != 0 && halfEven
        && ExactDecimal.shouldRound(magnitude, divisor, converted);
    if (round) {
      if (value < 0 && converted == Long.MIN_VALUE
          || value >= 0 && converted == Long.MAX_VALUE) {
        return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
      }
      converted += value < 0 ? -1 : 1;
    }
    if (!ExactDecimalDescriptors.valueFitsDescriptor(converted, targetDescriptor)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = converted;
    return StatusCode.OK;
  }

  static StatusCode halfAway(
      long value,
      int sourceDescriptor,
      int targetDescriptor,
      LongValue result,
      WideScratch scratch) {
    StatusCode status = apply(
        value, sourceDescriptor, targetDescriptor, false, false, result, scratch);
    if (!status.isOk()) return status;
    int sourceScale = ExactDecimalDescriptors.scale(sourceDescriptor);
    int targetScale = ExactDecimalDescriptors.scale(targetDescriptor);
    if (targetScale >= sourceScale) return StatusCode.OK;
    long divisor = ExactDecimal.powerOfTen(sourceScale - targetScale);
    long remainder = value % divisor;
    long magnitude = Math.abs(remainder);
    if (remainder == 0 || magnitude < divisor - magnitude) return StatusCode.OK;
    long rounded = result.value;
    if (value < 0 && rounded == Long.MIN_VALUE
        || value >= 0 && rounded == Long.MAX_VALUE) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    rounded += value < 0 ? -1 : 1;
    if (!ExactDecimalDescriptors.valueFitsDescriptor(rounded, targetDescriptor)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = rounded;
    return StatusCode.OK;
  }
}
