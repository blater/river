package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlScalarExpression;

/** Evaluates one bounded postfix exact-value expression without allocating. */
final class SqlScalarExpressionEvaluator {
  private final long[] values = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final ExactDecimal.LongValue numeric = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private final LocalTemporal.Value temporal = new LocalTemporal.Value();
  private int size;

  StatusCode evaluate(SqlScalarExpression expression, SqlExecutionResult result) {
    if (expression == null || !expression.isAvailable() || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    size = 0;
    StatusCode status = StatusCode.OK;
    for (int index = 0; status.isOk() && index < expression.nodeCount(); index++) {
      int operator = expression.operator(index);
      status = operator == SqlScalarExpression.LITERAL
          ? literal(expression.operand(index), expression.typeDescriptor(index))
          : operator == SqlScalarExpression.NEGATE
              || operator == SqlScalarExpression.ABSOLUTE
              || operator == SqlScalarExpression.CEILING
              || operator == SqlScalarExpression.FLOOR
              || operator == SqlScalarExpression.ROUND
              || operator == SqlScalarExpression.TRUNCATE
              || operator == SqlScalarExpression.CAST
              || operator == SqlScalarExpression.EXTRACT
                  ? unary(expression, index) : binary(expression, index);
    }
    if (!status.isOk() || size != 1) {
      return status.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : status;
    }
    result.setTypedScalar(values[0], descriptors[0], 0);
    return StatusCode.OK;
  }

  private StatusCode literal(long value, int descriptor) {
    if (size >= values.length || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    values[size] = value;
    descriptors[size] = descriptor;
    size++;
    return StatusCode.OK;
  }

  private StatusCode unary(SqlScalarExpression expression, int node) {
    if (size < 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = size - 1;
    long value = values[slot];
    int source = descriptors[slot];
    int target = expression.typeDescriptor(node);
    StatusCode status = switch (expression.operator(node)) {
      case SqlScalarExpression.NEGATE -> ExactDecimal.negate(value, source, numeric);
      case SqlScalarExpression.ABSOLUTE -> ExactDecimal.absolute(value, source, numeric);
      case SqlScalarExpression.CEILING ->
          ExactDecimal.integral(value, source, true, numeric);
      case SqlScalarExpression.FLOOR ->
          ExactDecimal.integral(value, source, false, numeric);
      case SqlScalarExpression.ROUND -> ExactDecimal.quantize(
          value, source, target, true, false, numeric, wide);
      case SqlScalarExpression.TRUNCATE -> ExactDecimal.quantize(
          value, source, target, false, false, numeric, wide);
      case SqlScalarExpression.CAST -> cast(value, source, target);
      case SqlScalarExpression.EXTRACT -> extract(value, source, expression.operand(node));
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
    if (status.isOk()) {
      values[slot] = numeric.value;
      descriptors[slot] = target;
    }
    return status;
  }

  private StatusCode cast(long value, int source, int target) {
    if (source == target) {
      numeric.value = value;
      return StatusCode.OK;
    }
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (sourceType == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        || targetType == SqlTypeDescriptor.TYPE_ID_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    boolean exact = targetType == SqlTypeDescriptor.TYPE_ID_BIGINT;
    return ExactDecimal.quantize(
        value, source, target, true, exact, numeric, wide);
  }

  private StatusCode extract(long value, int source, long field) {
    StatusCode status = field < Integer.MIN_VALUE || field > Integer.MAX_VALUE
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : LocalTemporal.extract(value, source, (int) field, temporal);
    if (status.isOk()) {
      numeric.value = temporal.value;
    }
    return status;
  }

  private StatusCode binary(SqlScalarExpression expression, int node) {
    if (size < 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int rightSlot = --size;
    int leftSlot = size - 1;
    long left = values[leftSlot];
    long right = values[rightSlot];
    int leftDescriptor = descriptors[leftSlot];
    int rightDescriptor = descriptors[rightSlot];
    int target = expression.typeDescriptor(node);
    int operator = expression.operator(node);
    StatusCode status = SqlTypeDescriptor.typeId(leftDescriptor)
            == SqlTypeDescriptor.TYPE_ID_DATE
        ? dateArithmetic(operator, left, right, rightDescriptor)
        : switch (operator) {
      case SqlScalarExpression.ADD -> ExactDecimal.add(
          left, leftDescriptor, right, rightDescriptor, false, target, numeric, wide);
      case SqlScalarExpression.SUBTRACT -> ExactDecimal.add(
          left, leftDescriptor, right, rightDescriptor, true, target, numeric, wide);
      case SqlScalarExpression.MULTIPLY -> ExactDecimal.multiply(
          left, leftDescriptor, right, rightDescriptor, target, numeric, wide);
      case SqlScalarExpression.DIVIDE -> ExactDecimal.divide(
          left, leftDescriptor, right, rightDescriptor, target, numeric, wide);
      case SqlScalarExpression.REMAINDER -> ExactDecimal.remainder(
          left, leftDescriptor, right, rightDescriptor, target, numeric, wide);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
    if (status.isOk()) {
      values[leftSlot] = numeric.value;
      descriptors[leftSlot] = target;
    }
    return status;
  }

  private StatusCode dateArithmetic(
      int operator, long left, long right, int rightDescriptor) {
    if (operator == SqlScalarExpression.ADD
        && SqlTypeDescriptor.typeId(rightDescriptor) == SqlTypeDescriptor.TYPE_ID_BIGINT) {
      return copyTemporal(LocalTemporal.addDateDays(left, right, temporal));
    }
    if (operator != SqlScalarExpression.SUBTRACT) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    StatusCode status = SqlTypeDescriptor.typeId(rightDescriptor)
            == SqlTypeDescriptor.TYPE_ID_DATE
        ? LocalTemporal.subtractDates(left, right, temporal)
        : SqlTypeDescriptor.typeId(rightDescriptor) == SqlTypeDescriptor.TYPE_ID_BIGINT
            ? LocalTemporal.subtractDateDays(left, right, temporal)
            : StatusCode.DATATYPE_MISMATCH;
    return copyTemporal(status);
  }

  private StatusCode copyTemporal(StatusCode status) {
    if (status.isOk()) {
      numeric.value = temporal.value;
    }
    return status;
  }
}
