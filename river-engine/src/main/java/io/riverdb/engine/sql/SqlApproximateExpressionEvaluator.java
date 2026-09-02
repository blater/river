package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlApproximateNumeric;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.sql.SqlScalarExpression;

/** Evaluates approximate scalar operations and checked compact conversions. */
final class SqlApproximateExpressionEvaluator {
  private final ExactDecimal.LongValue result = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch scratch = new ExactDecimal.WideScratch();

  StatusCode unary(
      int operator, long value, int source, int target, long scale) {
    double operand = SqlNumericValue.doubleValue(value, source);
    double computed = switch (operator) {
      case SqlScalarExpression.NEGATE -> -operand;
      case SqlScalarExpression.ABSOLUTE -> Math.abs(operand);
      case SqlScalarExpression.CEILING -> Math.ceil(operand);
      case SqlScalarExpression.FLOOR -> Math.floor(operand);
      case SqlScalarExpression.ROUND -> quantize(operand, scale, true);
      case SqlScalarExpression.TRUNCATE -> quantize(operand, scale, false);
      default -> Double.NaN;
    };
    return publish(computed, target);
  }

  StatusCode cast(long value, int source, int target) {
    StatusCode assigned = SqlNumericValue.assign(value, source, target, result, scratch);
    if (assigned != StatusCode.DATATYPE_MISMATCH
        || !SqlNumericTypeRules.isApproximate(source)
        || !SqlNumericTypeRules.isExact(target)) return assigned;
    double converted = SqlNumericValue.doubleValue(value, source);
    int scale = SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? SqlTypeDescriptor.parameterTwo(target) : 0;
    double rounded = Math.rint(converted * ExactDecimal.powerOfTen(scale));
    if (!Double.isFinite(rounded) || rounded < Long.MIN_VALUE || rounded > Long.MAX_VALUE) {
      return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    }
    result.value = (long) rounded;
    return SqlValueDomain.validFixed(target, result.value)
        ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
  }

  StatusCode binary(
      int operator,
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor,
      int target) {
    double leftValue = SqlNumericValue.doubleValue(left, leftDescriptor);
    double rightValue = SqlNumericValue.doubleValue(right, rightDescriptor);
    if ((operator == SqlScalarExpression.DIVIDE
        || operator == SqlScalarExpression.REMAINDER) && rightValue == 0.0d) {
      return StatusCode.DIVISION_BY_ZERO;
    }
    double computed = switch (operator) {
      case SqlScalarExpression.ADD -> leftValue + rightValue;
      case SqlScalarExpression.SUBTRACT -> leftValue - rightValue;
      case SqlScalarExpression.MULTIPLY -> leftValue * rightValue;
      case SqlScalarExpression.DIVIDE -> leftValue / rightValue;
      case SqlScalarExpression.REMAINDER -> leftValue % rightValue;
      default -> Double.NaN;
    };
    return publish(computed, target);
  }

  long value() { return result.value; }

  private static double quantize(double value, long scale, boolean round) {
    double factor = Math.pow(10.0d, scale);
    double scaled = value * factor;
    if (!Double.isFinite(scaled)) return value;
    double integral = round ? Math.rint(scaled)
        : scaled < 0.0d ? Math.ceil(scaled) : Math.floor(scaled);
    return integral / factor;
  }

  private StatusCode publish(double value, int descriptor) {
    if (!Double.isFinite(value)) return StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
    result.value = SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_REAL
        ? SqlApproximateNumeric.realBits((float) value)
        : SqlApproximateNumeric.doubleBits(value);
    return SqlValueDomain.validFixed(descriptor, result.value)
        ? StatusCode.OK : StatusCode.NUMERIC_VALUE_OUT_OF_RANGE;
  }
}
