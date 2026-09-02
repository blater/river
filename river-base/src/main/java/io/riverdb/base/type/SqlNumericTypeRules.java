package io.riverdb.base.type;

/** Dependency-neutral numeric families and widening rules. */
public final class SqlNumericTypeRules {
  private SqlNumericTypeRules() { }

  public static boolean isIntegral(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return SqlTypeDescriptor.isValid(descriptor)
        && (type == SqlTypeDescriptor.TYPE_ID_SMALLINT
            || type == SqlTypeDescriptor.TYPE_ID_INTEGER
            || type == SqlTypeDescriptor.TYPE_ID_BIGINT);
  }

  public static boolean isExact(int descriptor) {
    return isIntegral(descriptor)
        || SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
            && SqlTypeDescriptor.isValid(descriptor);
  }

  public static boolean isApproximate(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return SqlTypeDescriptor.isValid(descriptor)
        && (type == SqlTypeDescriptor.TYPE_ID_REAL
            || type == SqlTypeDescriptor.TYPE_ID_DOUBLE);
  }

  public static boolean isNumeric(int descriptor) {
    return isExact(descriptor) || isApproximate(descriptor);
  }

  public static boolean canImplicitlyCast(int source, int target) {
    if (!isNumeric(source) || !isNumeric(target)) return false;
    if (source == target) return true;
    if (isIntegral(source) && isIntegral(target)) {
      return integralRank(source) <= integralRank(target);
    }
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (targetType == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      if (sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL) return isIntegral(source);
      int sourceIntegerDigits = SqlTypeDescriptor.parameterOne(source)
          - SqlTypeDescriptor.parameterTwo(source);
      int targetIntegerDigits = SqlTypeDescriptor.parameterOne(target)
          - SqlTypeDescriptor.parameterTwo(target);
      return targetIntegerDigits >= sourceIntegerDigits
          && SqlTypeDescriptor.parameterTwo(target) >= SqlTypeDescriptor.parameterTwo(source);
    }
    if (targetType == SqlTypeDescriptor.TYPE_ID_DOUBLE) return true;
    if (targetType == SqlTypeDescriptor.TYPE_ID_REAL) {
      return isExact(source) || sourceType == SqlTypeDescriptor.TYPE_ID_REAL;
    }
    return false;
  }

  public static boolean canAssign(int source, int target) {
    if (!isNumeric(source) || !isNumeric(target)) return false;
    return canImplicitlyCast(source, target)
        || isExact(source) && isExact(target)
        || isApproximate(source) && isNumeric(target);
  }

  public static int integralRank(int descriptor) {
    return switch (SqlTypeDescriptor.typeId(descriptor)) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT -> 1;
      case SqlTypeDescriptor.TYPE_ID_INTEGER -> 2;
      case SqlTypeDescriptor.TYPE_ID_BIGINT -> 3;
      default -> 0;
    };
  }
}
