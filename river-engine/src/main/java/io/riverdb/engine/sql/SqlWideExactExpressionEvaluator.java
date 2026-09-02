package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal128Arithmetic;
import io.riverdb.base.type.ExactDecimal128Conversion;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.sql.SqlScalarExpression;

/** Evaluates scalar operations that contain a two-lane decimal operand or result. */
final class SqlWideExactExpressionEvaluator {
  private final ExactDecimal128.Value result = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch scratch = new ExactDecimal128.Scratch();

  StatusCode unary(
      int operator, long high, long value, int source, int target) {
    if (!SqlNumericTypeRules.isExact(source) || !SqlNumericTypeRules.isExact(target)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int sourcePrecision = precision(source);
    int sourceScale = scale(source);
    int targetPrecision = precision(target);
    int targetScale = scale(target);
    long sourceHigh = SqlTypeDescriptor.isWideDecimal(source) ? high : value >> 63;
    StatusCode status = switch (operator) {
      case SqlScalarExpression.NEGATE -> ExactDecimal128.negate(
          sourceHigh, value, sourcePrecision, result);
      case SqlScalarExpression.ABSOLUTE -> ExactDecimal128.absolute(
          sourceHigh, value, sourcePrecision, result);
      case SqlScalarExpression.CEILING -> ExactDecimal128Arithmetic.ceiling(
          sourceHigh, value, sourcePrecision, sourceScale,
          targetPrecision, result, scratch);
      case SqlScalarExpression.FLOOR -> ExactDecimal128Arithmetic.floor(
          sourceHigh, value, sourcePrecision, sourceScale,
          targetPrecision, result, scratch);
      case SqlScalarExpression.ROUND -> ExactDecimal128.quantize(
          sourceHigh, value, sourcePrecision, sourceScale,
          targetPrecision, targetScale, ExactDecimal128.ROUND_HALF_EVEN,
          false, result, scratch);
      case SqlScalarExpression.TRUNCATE -> ExactDecimal128.quantize(
          sourceHigh, value, sourcePrecision, sourceScale,
          targetPrecision, targetScale, ExactDecimal128.ROUND_TRUNCATE,
          false, result, scratch);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
    return validate(status, target);
  }

  StatusCode cast(long high, long value, int source, int target) {
    if (SqlTypeDescriptor.isWideDecimal(source)
        && SqlNumericTypeRules.isApproximate(target)) {
      return publishApproximate(
          SqlNumericComparison.doubleValue(high, value, source, scratch), target);
    }
    if (SqlNumericTypeRules.isApproximate(source)
        && SqlTypeDescriptor.isWideDecimal(target)) {
      return validate(ExactDecimal128Conversion.fromDouble(
          SqlNumericValue.doubleValue(value, source),
          SqlTypeDescriptor.parameterOne(target),
          SqlTypeDescriptor.parameterTwo(target), result, scratch), target);
    }
    if (!SqlNumericTypeRules.isExact(source) || !SqlNumericTypeRules.isExact(target)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return validate(ExactDecimal128.quantize(
        SqlTypeDescriptor.isWideDecimal(source) ? high : value >> 63,
        value, precision(source), scale(source), precision(target), scale(target),
        ExactDecimal128.ROUND_HALF_EVEN, SqlNumericTypeRules.isIntegral(target),
        result, scratch), target);
  }

  StatusCode binary(
      int operator,
      long leftHigh,
      long left,
      int leftDescriptor,
      long rightHigh,
      long right,
      int rightDescriptor,
      int target) {
    if (SqlNumericTypeRules.isApproximate(target)) {
      return approximate(
          operator,
          SqlNumericComparison.doubleValue(leftHigh, left, leftDescriptor, scratch),
          SqlNumericComparison.doubleValue(rightHigh, right, rightDescriptor, scratch),
          target);
    }
    if (!SqlNumericTypeRules.isExact(leftDescriptor)
        || !SqlNumericTypeRules.isExact(rightDescriptor)
        || !SqlNumericTypeRules.isExact(target)) return StatusCode.DATATYPE_MISMATCH;
    long normalizedLeftHigh = SqlTypeDescriptor.isWideDecimal(leftDescriptor)
        ? leftHigh : left >> 63;
    long normalizedRightHigh = SqlTypeDescriptor.isWideDecimal(rightDescriptor)
        ? rightHigh : right >> 63;
    int leftPrecision = precision(leftDescriptor);
    int leftScale = scale(leftDescriptor);
    int rightPrecision = precision(rightDescriptor);
    int rightScale = scale(rightDescriptor);
    int targetPrecision = precision(target);
    int targetScale = scale(target);
    StatusCode status = switch (operator) {
      case SqlScalarExpression.ADD, SqlScalarExpression.SUBTRACT -> ExactDecimal128.add(
          normalizedLeftHigh, left, leftPrecision, leftScale,
          normalizedRightHigh, right, rightPrecision, rightScale,
          operator == SqlScalarExpression.SUBTRACT,
          targetPrecision, targetScale, result, scratch);
      case SqlScalarExpression.MULTIPLY -> ExactDecimal128Arithmetic.multiply(
          normalizedLeftHigh, left, leftPrecision, leftScale,
          normalizedRightHigh, right, rightPrecision, rightScale,
          targetPrecision, targetScale, result, scratch);
      case SqlScalarExpression.DIVIDE -> ExactDecimal128Arithmetic.divide(
          normalizedLeftHigh, left, leftPrecision, leftScale,
          normalizedRightHigh, right, rightPrecision, rightScale,
          targetPrecision, targetScale, result, scratch);
      case SqlScalarExpression.REMAINDER -> ExactDecimal128Arithmetic.remainder(
          normalizedLeftHigh, left, leftPrecision, leftScale,
          normalizedRightHigh, right, rightPrecision, rightScale,
          targetPrecision, targetScale, result, scratch);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
    return validate(status, target);
  }

  long high() { return result.high; }
  long low() { return result.low; }

  static boolean required(int first, int second) {
    return SqlTypeDescriptor.isWideDecimal(first)
        || SqlTypeDescriptor.isWideDecimal(second);
  }

  static boolean required(int first, int second, int third) {
    return required(first, second) || SqlTypeDescriptor.isWideDecimal(third);
  }

  private StatusCode approximate(
      int operator, double left, double right, int target) {
    if ((operator == SqlScalarExpression.DIVIDE
        || operator == SqlScalarExpression.REMAINDER) && right == 0.0d) {
      return StatusCode.DIVISION_BY_ZERO;
    }
    double value = switch (operator) {
      case SqlScalarExpression.ADD -> left + right;
      case SqlScalarExpression.SUBTRACT -> left - right;
      case SqlScalarExpression.MULTIPLY -> left * right;
      case SqlScalarExpression.DIVIDE -> left / right;
      case SqlScalarExpression.REMAINDER -> left % right;
      default -> Double.NaN;
    };
    return publishApproximate(value, target);
  }

  private StatusCode publishApproximate(double value, int descriptor) {
    if (!Double.isFinite(value)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    result.low = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_REAL
        ? SqlApproximateNumeric.realBits((float) value)
        : SqlApproximateNumeric.doubleBits(value);
    result.high = result.low >> 63;
    return SqlValueDomain.validFixed(descriptor, result.low)
        ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
  }

  private StatusCode validate(StatusCode status, int target) {
    if (!status.isOk()) return status;
    if (!SqlTypeDescriptor.isWideDecimal(target)
        && (result.high != result.low >> 63
            || !SqlValueDomain.validFixed(target, result.low))) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    return StatusCode.OK;
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

  private static int scale(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(descriptor) : 0;
  }
}
