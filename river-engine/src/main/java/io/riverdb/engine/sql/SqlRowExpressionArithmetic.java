package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.LocalTemporalCast;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlScalarExpression;

/** Owns numeric and temporal operators over the evaluator's reusable value stack. */
final class SqlRowExpressionArithmetic {
  private final SqlRowExpressionEvaluator evaluator;
  private final SqlTemporalContext temporal;
  private final LocalTemporal.Value temporalValue = new LocalTemporal.Value();
  private final LocalTemporalCast.TextResult textResult = new LocalTemporalCast.TextResult();
  private final SqlNumericExpressionEvaluator exact = new SqlNumericExpressionEvaluator();

  SqlRowExpressionArithmetic(
      SqlRowExpressionEvaluator rowEvaluator, SqlTemporalContext temporalContext) {
    evaluator = rowEvaluator;
    temporal = temporalContext;
  }

  StatusCode unary(int operator, long operand, int target, SqlTemporalZonePlan zone) {
    if (evaluator.size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = evaluator.size - 1;
    if (evaluator.nulls[slot]) {
      evaluator.descriptors[slot] = target;
      return StatusCode.OK;
    }
    int source = evaluator.descriptors[slot];
    StatusCode status = switch (operator) {
      case SqlScalarExpression.NEGATE,
          SqlScalarExpression.ABSOLUTE,
          SqlScalarExpression.CEILING,
          SqlScalarExpression.FLOOR,
          SqlScalarExpression.ROUND,
          SqlScalarExpression.TRUNCATE ->
          exact.unary(operator, evaluator.highs[slot], evaluator.values[slot], source, target, operand);
      case SqlScalarExpression.CAST -> cast(
          evaluator.highs[slot], evaluator.values[slot], source, target);
      case SqlScalarExpression.AT_TIME_ZONE -> temporal.atTimeZone(
          evaluator.values[slot], source, zone, evaluator.longResult);
      case SqlScalarExpression.EXTRACT -> extract(
          evaluator.values[slot], source, operand);
      default -> StatusCode.FEATURE_NOT_SUPPORTED;
    };
    if (status.isOk()) {
      evaluator.values[slot] = operator == SqlScalarExpression.EXTRACT
          ? temporalValue.value : SqlNumericExpressionEvaluator.unaryOperator(operator)
              ? exact.value() : evaluator.longResult.value;
      boolean numeric = SqlNumericExpressionEvaluator.unaryOperator(operator)
          || operator == SqlScalarExpression.CAST
              && SqlNumericTypeRules.isNumeric(source)
              && SqlNumericTypeRules.isNumeric(target);
      evaluator.highs[slot] = numeric
          ? exact.highValue() : evaluator.values[slot] >> 63;
      evaluator.descriptors[slot] = target;
    }
    return status;
  }

  private StatusCode cast(long high, long value, int source, int target) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (SqlNumericTypeRules.isNumeric(source)
        && SqlNumericTypeRules.isNumeric(target)) {
      StatusCode status = exact.cast(high, value, source, target);
      evaluator.longResult.value = exact.value();
      return status;
    }
    if (targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = temporal.formatTemporal(
          value, source, target, evaluator.text.writableCharacters(), textResult);
      if (status.isOk()) evaluator.text.publish(textResult.length);
      else evaluator.text.clear();
      evaluator.longResult.value = 0;
      return status;
    }
    if (sourceType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      StatusCode status = LocalTemporalCast.parseText(
          evaluator.text, 0, evaluator.text.length(), target, temporalValue);
      evaluator.longResult.value = temporalValue.value;
      return status;
    }
    return temporal.castTemporal(value, source, target, evaluator.longResult);
  }

  private StatusCode extract(long value, int source, long field) {
    return field < Integer.MIN_VALUE || field > Integer.MAX_VALUE
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : LocalTemporal.extract(value, source, (int) field, temporalValue);
  }

  StatusCode binary(int operator, int target) {
    if (evaluator.size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int right = --evaluator.size;
    int left = evaluator.size - 1;
    if (evaluator.nulls[left] || evaluator.nulls[right]) {
      evaluator.nulls[left] = true;
      evaluator.descriptors[left] = target;
      return StatusCode.OK;
    }
    int leftDescriptor = evaluator.descriptors[left];
    int rightDescriptor = evaluator.descriptors[right];
    int rightType = SqlTypeDescriptor.typeId(rightDescriptor);
    boolean date = SqlTypeDescriptor.typeId(leftDescriptor)
        == SqlTypeDescriptor.TYPE_ID_DATE;
    StatusCode status = date
        ? operator == SqlScalarExpression.ADD
            ? LocalTemporal.addDateDays(
                evaluator.values[left], evaluator.values[right], temporalValue)
            : rightType == SqlTypeDescriptor.TYPE_ID_DATE
                ? LocalTemporal.subtractDates(
                    evaluator.values[left], evaluator.values[right], temporalValue)
                : LocalTemporal.subtractDateDays(
                    evaluator.values[left], evaluator.values[right], temporalValue)
        : exact.binary(
            operator,
            evaluator.highs[left], evaluator.values[left],
            leftDescriptor,
            evaluator.highs[right], evaluator.values[right],
            rightDescriptor,
            target);
    if (status.isOk()) {
      evaluator.values[left] = date ? temporalValue.value : exact.value();
      evaluator.highs[left] = date ? evaluator.values[left] >> 63 : exact.highValue();
      evaluator.descriptors[left] = target;
    }
    return status;
  }
}
