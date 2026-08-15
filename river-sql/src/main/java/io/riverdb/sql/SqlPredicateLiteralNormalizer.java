package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Normalizes bounded predicate literals to one exact comparison descriptor. */
final class SqlPredicateLiteralNormalizer {
  private final ExactDecimal.LongValue decimal = new ExactDecimal.LongValue();
  private long lower;
  private long upper;
  private long value;
  private int descriptor;

  StatusCode normalizeRange(
      long lowerValue,
      int lowerDescriptor,
      long upperValue,
      int upperDescriptor) {
    int common = commonDescriptor(lowerDescriptor, upperDescriptor);
    if (common == 0) {
      return incompatibleStatus(lowerDescriptor, upperDescriptor);
    }
    lower = lowerValue;
    upper = upperValue;
    descriptor = common;
    if (noRawConversion(common)) {
      return StatusCode.OK;
    }
    if (!ExactDecimal.widenScale(lower, lowerDescriptor, common, decimal)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    lower = decimal.value;
    if (!ExactDecimal.widenScale(upper, upperDescriptor, common, decimal)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    upper = decimal.value;
    return StatusCode.OK;
  }

  StatusCode normalizeMembership(
      long[] values,
      int count,
      int currentDescriptor,
      long candidate,
      int candidateDescriptor) {
    int common = commonDescriptor(currentDescriptor, candidateDescriptor);
    if (common == 0) {
      return incompatibleStatus(currentDescriptor, candidateDescriptor);
    }
    value = candidate;
    descriptor = common;
    if (noRawConversion(common)) {
      return StatusCode.OK;
    }
    for (int index = 0; index < count; index++) {
      if (!ExactDecimal.widenScale(
          values[index], currentDescriptor, common, decimal)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      values[index] = decimal.value;
    }
    if (!ExactDecimal.widenScale(
        candidate, candidateDescriptor, common, decimal)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    value = decimal.value;
    return StatusCode.OK;
  }

  StatusCode mergeMembershipDescriptor(
      long[] values,
      int count,
      int currentDescriptor,
      int candidateDescriptor) {
    if (currentDescriptor == 0 || currentDescriptor == candidateDescriptor) {
      descriptor = candidateDescriptor;
      return StatusCode.OK;
    }
    return normalizeMembership(
        values, count, currentDescriptor, 0, candidateDescriptor);
  }

  long lower() {
    return lower;
  }

  long upper() {
    return upper;
  }

  long value() {
    return value;
  }

  int descriptor() {
    return descriptor;
  }

  private static int commonDescriptor(int left, int right) {
    if (left == right && SqlTypeDescriptor.isValid(left)) return left;
    int leftType = SqlTypeDescriptor.typeId(left);
    int rightType = SqlTypeDescriptor.typeId(right);
    if (leftType == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && rightType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return SqlTypeDescriptor.varchar(Math.max(
          SqlTypeDescriptor.parameterOne(left), SqlTypeDescriptor.parameterOne(right)));
    }
    if (leftType == rightType && parameterizedTemporal(leftType)) {
      return temporalDescriptor(
          leftType,
          Math.max(
              SqlTypeDescriptor.parameterOne(left),
              SqlTypeDescriptor.parameterOne(right)));
    }
    if (leftType != SqlTypeDescriptor.TYPE_ID_DECIMAL
        || rightType != SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      return 0;
    }
    int scale = Math.max(
        SqlTypeDescriptor.parameterTwo(left), SqlTypeDescriptor.parameterTwo(right));
    int integerDigits = Math.max(
        SqlTypeDescriptor.parameterOne(left) - SqlTypeDescriptor.parameterTwo(left),
        SqlTypeDescriptor.parameterOne(right) - SqlTypeDescriptor.parameterTwo(right));
    return SqlTypeDescriptor.decimal(integerDigits + scale, scale);
  }

  private static int temporalDescriptor(int type, int precision) {
    return switch (type) {
      case SqlTypeDescriptor.TYPE_ID_TIME -> SqlTypeDescriptor.time(precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP -> SqlTypeDescriptor.timestamp(precision);
      case SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE ->
          SqlTypeDescriptor.timestampWithTimeZone(precision);
      default -> 0;
    };
  }

  private static StatusCode incompatibleStatus(int left, int right) {
    return temporal(left) && temporal(right)
        ? StatusCode.DATATYPE_MISMATCH : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean noRawConversion(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor)
        != SqlTypeDescriptor.TYPE_ID_DECIMAL;
  }

  private static boolean temporal(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_DATE || parameterizedTemporal(type);
  }

  private static boolean parameterizedTemporal(int type) {
    return type == SqlTypeDescriptor.TYPE_ID_TIME
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP
        || type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
  }
}
