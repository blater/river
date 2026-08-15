package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlScalarExpression;

/** Evaluates exact-numeric nodes for bound scalar programs. */
final class SqlExactExpressionEvaluator {
  private final ExactDecimal.LongValue result = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();

  StatusCode unary(int operator, long value, int source, int target) {
    return switch (operator) {
      case SqlScalarExpression.NEGATE -> ExactDecimal.negate(value, source, result);
      case SqlScalarExpression.ABSOLUTE -> ExactDecimal.absolute(value, source, result);
      case SqlScalarExpression.CEILING -> ExactDecimal.integral(value, source, true, result);
      case SqlScalarExpression.FLOOR -> ExactDecimal.integral(value, source, false, result);
      case SqlScalarExpression.ROUND ->
          ExactDecimal.quantize(value, source, target, true, false, result, wide);
      case SqlScalarExpression.TRUNCATE ->
          ExactDecimal.quantize(value, source, target, false, false, result, wide);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
  }

  StatusCode cast(long value, int source, int target) {
    return ExactDecimal.quantize(
        value, source, target, true, target == SqlTypeDescriptor.BIGINT,
        result, wide);
  }

  StatusCode binary(
      int operator,
      long left,
      int leftDescriptor,
      long right,
      int rightDescriptor,
      int target) {
    return switch (operator) {
      case SqlScalarExpression.ADD -> ExactDecimal.add(
          left, leftDescriptor, right, rightDescriptor, false, target, result, wide);
      case SqlScalarExpression.SUBTRACT -> ExactDecimal.add(
          left, leftDescriptor, right, rightDescriptor, true, target, result, wide);
      case SqlScalarExpression.MULTIPLY -> ExactDecimal.multiply(
          left, leftDescriptor, right, rightDescriptor, target, result, wide);
      case SqlScalarExpression.DIVIDE -> ExactDecimal.divide(
          left, leftDescriptor, right, rightDescriptor, target, result, wide);
      case SqlScalarExpression.REMAINDER -> ExactDecimal.remainder(
          left, leftDescriptor, right, rightDescriptor, target, result, wide);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
  }

  long value() {
    return result.value;
  }

  static boolean unaryOperator(int operator) {
    return operator == SqlScalarExpression.NEGATE
        || operator == SqlScalarExpression.ABSOLUTE
        || operator == SqlScalarExpression.CEILING
        || operator == SqlScalarExpression.FLOOR
        || operator == SqlScalarExpression.ROUND
        || operator == SqlScalarExpression.TRUNCATE;
  }
}
