package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlNumericTypeRules;

/** Selects compact or two-lane exact expression arithmetic without allocating. */
final class SqlNumericExpressionEvaluator {
  private final SqlExactExpressionEvaluator compact = new SqlExactExpressionEvaluator();
  private final SqlApproximateExpressionEvaluator approximate =
      new SqlApproximateExpressionEvaluator();
  private final SqlWideExactExpressionEvaluator wide = new SqlWideExactExpressionEvaluator();
  private int mode;

  StatusCode unary(
      int operator, long high, long value, int source, int target, long operand) {
    mode = SqlWideExactExpressionEvaluator.required(source, target) ? 2
        : SqlNumericTypeRules.isApproximate(source) ? 1 : 0;
    return mode == 2
        ? wide.unary(operator, high, value, source, target)
        : mode == 1 ? approximate.unary(operator, value, source, target, operand)
            : compact.unary(operator, value, source, target);
  }

  StatusCode cast(long high, long value, int source, int target) {
    mode = SqlWideExactExpressionEvaluator.required(source, target) ? 2
        : SqlNumericTypeRules.isApproximate(source)
            || SqlNumericTypeRules.isApproximate(target) ? 1 : 0;
    return mode == 2
        ? wide.cast(high, value, source, target)
        : mode == 1 ? approximate.cast(value, source, target)
            : compact.cast(value, source, target);
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
    mode = SqlWideExactExpressionEvaluator.required(
        leftDescriptor, rightDescriptor, target) ? 2
        : SqlNumericTypeRules.isApproximate(target) ? 1 : 0;
    return mode == 2
        ? wide.binary(
            operator,
            leftHigh, left, leftDescriptor,
            rightHigh, right, rightDescriptor,
            target)
        : mode == 1 ? approximate.binary(
            operator, left, leftDescriptor, right, rightDescriptor, target)
            : compact.binary(
            operator, left, leftDescriptor, right, rightDescriptor, target);
  }

  long value() {
    return mode == 2 ? wide.low() : mode == 1 ? approximate.value() : compact.value();
  }
  long highValue() { return mode == 2 ? wide.high() : value() >> 63; }

  void seed(long value) {
    mode = 0;
    compact.seed(value);
  }

  static boolean unaryOperator(int operator) {
    return SqlExactExpressionEvaluator.unaryOperator(operator);
  }
}
