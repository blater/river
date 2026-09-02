package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Lossless common output descriptors for UNION operands. */
final class SqlUnionTypeResolver {
  private SqlUnionTypeResolver() { }

  static int common(int left, int right) {
    if (left == right && SqlTypeDescriptor.isValid(left)) return left;
    if (SqlNumericTypeRules.isNumeric(left) && SqlNumericTypeRules.isNumeric(right)) {
      return numeric(left, right);
    }
    int leftType = SqlTypeDescriptor.typeId(left);
    int rightType = SqlTypeDescriptor.typeId(right);
    if (leftType != rightType) return 0;
    if (leftType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return SqlTypeDescriptor.varchar(Math.max(
          SqlTypeDescriptor.parameterOne(left), SqlTypeDescriptor.parameterOne(right)));
    }
    if (leftType == SqlTypeDescriptor.TYPE_ID_TIME
        || leftType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || leftType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE) {
      int precision = Math.max(
          SqlTypeDescriptor.parameterOne(left), SqlTypeDescriptor.parameterOne(right));
      return leftType == SqlTypeDescriptor.TYPE_ID_TIME
          ? SqlTypeDescriptor.time(precision)
          : leftType == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
              ? SqlTypeDescriptor.timestamp(precision)
              : SqlTypeDescriptor.timestampWithTimeZone(precision);
    }
    return 0;
  }

  private static int numeric(int left, int right) {
    if (SqlNumericTypeRules.isApproximate(left)
        || SqlNumericTypeRules.isApproximate(right)) {
      return left == SqlTypeDescriptor.REAL && right == SqlTypeDescriptor.REAL
          ? SqlTypeDescriptor.REAL : SqlTypeDescriptor.DOUBLE;
    }
    if (SqlNumericTypeRules.isIntegral(left) && SqlNumericTypeRules.isIntegral(right)) {
      return SqlNumericTypeRules.integralRank(left) >= SqlNumericTypeRules.integralRank(right)
          ? left : right;
    }
    int scale = Math.max(scale(left), scale(right));
    int integer = Math.max(precision(left) - scale(left), precision(right) - scale(right));
    return integer + scale > SqlTypeDescriptor.MAXIMUM_DECIMAL_PRECISION
        ? 0 : SqlTypeDescriptor.decimal(Math.max(1, integer + scale), scale);
  }

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
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
