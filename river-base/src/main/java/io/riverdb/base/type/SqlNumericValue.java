package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Allocation-free canonical numeric coercion and comparison over River's long value lane. */
public final class SqlNumericValue {
  private SqlNumericValue() { }

  public static StatusCode assign(
      long value, int source, int target,
      ExactDecimal.LongValue result, ExactDecimal.WideScratch scratch) {
    if (result == null || scratch == null
        || !SqlNumericTypeRules.isNumeric(source)
        || !SqlNumericTypeRules.isNumeric(target)) return StatusCode.DATATYPE_MISMATCH;
    if (source == target) return publish(value, target, result);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (targetType == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && SqlNumericTypeRules.isExact(source)) {
      return ExactDecimal.quantizeHalfAway(value, source, target, result, scratch);
    }
    if (SqlNumericTypeRules.isIntegral(target)
        && SqlNumericTypeRules.isExact(source)) {
      return ExactDecimal.quantizeHalfAway(value, source, target, result, scratch);
    }
    if (SqlNumericTypeRules.isExact(target)
        && SqlNumericTypeRules.isApproximate(source)) {
      return approximateToExact(value, source, target, result);
    }
    if (SqlNumericTypeRules.isApproximate(target)) {
      double converted = doubleValue(value, source);
      long bits = targetType == SqlTypeDescriptor.TYPE_ID_REAL
          ? SqlApproximateNumeric.realBits((float) converted)
          : SqlApproximateNumeric.doubleBits(converted);
      return publish(bits, target, result);
    }
    return StatusCode.DATATYPE_MISMATCH;
  }

  private static StatusCode approximateToExact(
      long bits, int source, int target, ExactDecimal.LongValue result) {
    double value = doubleValue(bits, source);
    int scale = SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(target) : 0;
    double scaled = value * ExactDecimal.powerOfTen(scale);
    if (!Double.isFinite(scaled)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    double rounded = scaled < 0.0d
        ? Math.ceil(scaled - 0.5d) : Math.floor(scaled + 0.5d);
    if (rounded < Long.MIN_VALUE || rounded > Long.MAX_VALUE) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    return publish((long) rounded, target, result);
  }

  public static int compare(long left, int leftDescriptor, long right, int rightDescriptor) {
    if (SqlNumericTypeRules.isExact(leftDescriptor)
        && SqlNumericTypeRules.isExact(rightDescriptor)) {
      return ExactDecimal.compare(left, leftDescriptor, right, rightDescriptor);
    }
    double leftValue = doubleValue(left, leftDescriptor);
    double rightValue = doubleValue(right, rightDescriptor);
    return leftValue < rightValue ? -1 : leftValue > rightValue ? 1 : 0;
  }

  public static double doubleValue(long value, int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_DECIMAL ->
          (double) value / ExactDecimal.powerOfTen(SqlTypeDescriptor.parameterTwo(descriptor));
      case SqlTypeDescriptor.TYPE_ID_REAL -> Float.intBitsToFloat((int) value);
      case SqlTypeDescriptor.TYPE_ID_DOUBLE -> Double.longBitsToDouble(value);
      default -> (double) value;
    };
  }

  private static StatusCode publish(
      long value, int descriptor, ExactDecimal.LongValue result) {
    if (!SqlValueDomain.validFixed(descriptor, value)) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = value;
    return StatusCode.OK;
  }
}
