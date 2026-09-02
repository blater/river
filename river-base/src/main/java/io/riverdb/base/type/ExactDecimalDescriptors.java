package io.riverdb.base.type;

/** SQL result-shape rules for exact numeric operations. */
final class ExactDecimalDescriptors {
  private ExactDecimalDescriptors() {
  }

  static int binaryResult(int left, int right, int operation) {
    if (!exactNumeric(left) || !exactNumeric(right)) {
      return 0;
    }
    if (SqlNumericTypeRules.isIntegral(left) && SqlNumericTypeRules.isIntegral(right)) {
      return SqlNumericTypeRules.integralRank(left) >= SqlNumericTypeRules.integralRank(right)
          ? left : right;
    }
    int leftScale = scale(left);
    int rightScale = scale(right);
    int leftInteger = precision(left) - leftScale;
    int rightInteger = precision(right) - rightScale;
    int integerDigits;
    int resultScale;
    if (operation == 0) {
      integerDigits = Math.max(leftInteger, rightInteger) + 1;
      resultScale = Math.max(leftScale, rightScale);
    } else if (operation == 1) {
      integerDigits = leftInteger + rightInteger;
      resultScale = leftScale + rightScale;
    } else if (operation == 2) {
      integerDigits = leftInteger + rightScale;
      resultScale = Math.max(6, leftScale + precision(right) + 1);
    } else {
      integerDigits = Math.min(leftInteger, rightInteger);
      resultScale = Math.max(leftScale, rightScale);
    }
    return bounded(integerDigits, resultScale);
  }

  static int quantized(int source, int targetScale) {
    if (!exactNumeric(source) || targetScale < 0
        || targetScale > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION) {
      return 0;
    }
    if (SqlNumericTypeRules.isIntegral(source)) {
      return targetScale == 0 ? source : 0;
    }
    return bounded(precision(source) - scale(source), targetScale);
  }

  static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }

  static boolean exactNumeric(int descriptor) {
    return SqlNumericTypeRules.isExact(descriptor);
  }

  static boolean validOperation(
      int left,
      int right,
      int target,
      ExactDecimal.LongValue result,
      ExactDecimal.WideScratch scratch) {
    return result != null && scratch != null && exactNumeric(left)
        && exactNumeric(right) && exactNumeric(target);
  }

  static boolean valueFitsDescriptor(long value, int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal.fits(value, SqlTypeDescriptor.parameterOne(descriptor))
        : SqlValueDomain.validFixed(descriptor, value);
  }

  private static int bounded(int integerDigits, int naturalScale) {
    int boundedInteger = Math.max(0, integerDigits);
    int boundedScale = boundedInteger >= SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION
        ? 0 : Math.min(
            naturalScale,
            SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION - boundedInteger);
    int precision = Math.min(
        SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION,
        Math.max(1, boundedInteger + boundedScale));
    return SqlTypeDescriptor.decimal(precision, boundedScale);
  }

  private static int precision(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> 5;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> 10;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> 19;
      case SqlTypeDescriptor.TYPE_ID_DECIMAL -> SqlTypeDescriptor.parameterOne(descriptor);
      default -> 0;
    };
  }
}
