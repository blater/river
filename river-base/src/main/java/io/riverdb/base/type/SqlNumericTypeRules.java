package io.riverdb.base.type;

/** Dependency-neutral numeric families and widening rules. */
public final class SqlNumericTypeRules {
  private SqlNumericTypeRules() { }

  public static boolean isIntegral(int descriptor) {
    return SqlTypeDescriptor.isValid(descriptor) && integralRank(descriptor) != 0;
  }

  public static boolean isExact(int descriptor) {
    return SqlTypeDescriptor.isValid(descriptor)
        && (integralRank(descriptor) != 0
            || SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL);
  }

  public static boolean isApproximate(int descriptor) {
    return SqlTypeDescriptor.isValid(descriptor)
        && isApproximateType(SqlTypeDescriptor.typeId(descriptor));
  }

  private static boolean isApproximateType(int type) {
    return type == SqlTypeDescriptor.TYPE_ID_REAL || type == SqlTypeDescriptor.TYPE_ID_DOUBLE;
  }

  public static boolean isNumeric(int descriptor) {
    return SqlTypeDescriptor.isValid(descriptor)
        && isNumericType(SqlTypeDescriptor.typeId(descriptor));
  }

  static boolean isNumericType(int type) {
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_SMALLINT, SqlTypeDescriptor.TYPE_ID_INTEGER,
          SqlTypeDescriptor.TYPE_ID_BIGINT, SqlTypeDescriptor.TYPE_ID_DECIMAL,
          SqlTypeDescriptor.TYPE_ID_REAL, SqlTypeDescriptor.TYPE_ID_DOUBLE -> true;
      default -> false;
    };
  }

  public static boolean canImplicitlyCast(int source, int target) {
    if (!isNumeric(source) || !isNumeric(target)) return false;
    return canWiden(source, target);
  }

  /** Both descriptors have been admitted and classified as numeric. */
  static boolean canWiden(int source, int target) {
    if (source == target) return true;
    if (integralRank(source) != 0 && integralRank(target) != 0) {
      return integralRank(source) <= integralRank(target);
    }
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (targetType == SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      if (sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL) return integralRank(source) != 0;
      int sourceIntegerDigits = SqlTypeDescriptor.parameterOne(source)
          - SqlTypeDescriptor.parameterTwo(source);
      int targetIntegerDigits = SqlTypeDescriptor.parameterOne(target)
          - SqlTypeDescriptor.parameterTwo(target);
      return targetIntegerDigits >= sourceIntegerDigits
          && SqlTypeDescriptor.parameterTwo(target) >= SqlTypeDescriptor.parameterTwo(source);
    }
    if (targetType == SqlTypeDescriptor.TYPE_ID_DOUBLE) return true;
    if (targetType == SqlTypeDescriptor.TYPE_ID_REAL) {
      return sourceType != SqlTypeDescriptor.TYPE_ID_DOUBLE;
    }
    return false;
  }

  public static boolean canAssign(int source, int target) {
    if (!isNumeric(source) || !isNumeric(target)) return false;
    return canWiden(source, target)
        || !isApproximateType(SqlTypeDescriptor.typeId(source))
            && !isApproximateType(SqlTypeDescriptor.typeId(target))
        || isApproximateType(SqlTypeDescriptor.typeId(source));
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
