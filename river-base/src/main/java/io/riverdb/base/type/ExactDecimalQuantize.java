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
    if (halfEven && remainder != 0
        && ExactDecimal.shouldRound(Math.abs(remainder), divisor, converted)) {
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
}
