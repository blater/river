package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlScalarExpression;

/** Evaluates one bounded postfix exact-value expression without allocating. */
final class SqlScalarExpressionEvaluator {
  private final long[] values = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final long[] highs = new long[SqlScalarExpression.MAXIMUM_NODES];
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final SqlNumericExpressionEvaluator numeric = new SqlNumericExpressionEvaluator();
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
          ? literal(
              expression.operandHigh(index), expression.operand(index),
              expression.typeDescriptor(index))
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
    return result.setTypedScalar(highs[0], values[0], descriptors[0], 0);
  }

  private StatusCode literal(long high, long value, int descriptor) {
    if (size >= values.length || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    values[size] = value;
    highs[size] = high;
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
      case SqlScalarExpression.NEGATE,
          SqlScalarExpression.ABSOLUTE,
          SqlScalarExpression.CEILING,
          SqlScalarExpression.FLOOR,
          SqlScalarExpression.ROUND,
          SqlScalarExpression.TRUNCATE -> numeric.unary(
              expression.operator(node), highs[slot], value, source, target,
              expression.operand(node));
      case SqlScalarExpression.CAST -> cast(highs[slot], value, source, target);
      case SqlScalarExpression.EXTRACT -> extract(value, source, expression.operand(node));
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
    if (status.isOk()) {
      values[slot] = numeric.value();
      highs[slot] = numeric.highValue();
      descriptors[slot] = target;
    }
    return status;
  }

  private StatusCode cast(long high, long value, int source, int target) {
    if (source == target) return numeric.cast(high, value, source, target);
    int sourceType = SqlTypeDescriptor.typeId(source);
    int targetType = SqlTypeDescriptor.typeId(target);
    if (sourceType == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        || targetType == SqlTypeDescriptor.TYPE_ID_BOOLEAN) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    return numeric.cast(high, value, source, target);
  }

  private StatusCode extract(long value, int source, long field) {
    StatusCode status = field < Integer.MIN_VALUE || field > Integer.MAX_VALUE
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : LocalTemporal.extract(value, source, (int) field, temporal);
    if (status.isOk()) {
      numeric.seed(temporal.value);
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
      case SqlScalarExpression.ADD,
          SqlScalarExpression.SUBTRACT,
          SqlScalarExpression.MULTIPLY,
          SqlScalarExpression.DIVIDE,
          SqlScalarExpression.REMAINDER -> numeric.binary(
              operator,
              highs[leftSlot], left, leftDescriptor,
              highs[rightSlot], right, rightDescriptor,
              target);
      default -> StatusCode.INVALID_EXTERNAL_INPUT;
    };
    if (status.isOk()) {
      values[leftSlot] = numeric.value();
      highs[leftSlot] = numeric.highValue();
      descriptors[leftSlot] = target;
    }
    return status;
  }

  private StatusCode dateArithmetic(
      int operator, long left, long right, int rightDescriptor) {
    boolean integral = SqlNumericTypeRules.isIntegral(rightDescriptor);
    if (operator == SqlScalarExpression.ADD
        && integral) {
      return copyTemporal(LocalTemporal.addDateDays(left, right, temporal));
    }
    if (operator != SqlScalarExpression.SUBTRACT) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    StatusCode status = SqlTypeDescriptor.typeId(rightDescriptor)
            == SqlTypeDescriptor.TYPE_ID_DATE
        ? LocalTemporal.subtractDates(left, right, temporal)
        : integral
            ? LocalTemporal.subtractDateDays(left, right, temporal)
            : StatusCode.DATATYPE_MISMATCH;
    return copyTemporal(status);
  }

  private StatusCode copyTemporal(StatusCode status) {
    if (status.isOk()) {
      numeric.seed(temporal.value);
    }
    return status;
  }
}
