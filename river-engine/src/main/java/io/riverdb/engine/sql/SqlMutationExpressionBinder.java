package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds the shared fixed-width mutation-expression arena. */
final class SqlMutationExpressionBinder {
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] untypedNulls =
      new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private int size;

  StatusCode bind(
      SqlCommand command,
      BoundSqlStatement bound,
      int expression,
      boolean columnsAllowed) {
    bound.projectionPrograms.beginMutation(expression);
    size = 0;
    for (int node = 0;
        node < command.mutationExpressions().nodeCount(expression);
        node++) {
      StatusCode status = bindNode(
          command, bound, expression, node, columnsAllowed);
      if (!status.isOk()) return status;
    }
    if (size != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!fixedWidth(descriptors[0])) return StatusCode.FEATURE_NOT_SUPPORTED;
    bound.projectionPrograms.finishMutation(expression, descriptors[0]);
    return bound.projectionPrograms.status();
  }

  private StatusCode bindNode(
      SqlCommand command,
      BoundSqlStatement bound,
      int expression,
      int node,
      boolean columnsAllowed) {
    int operator = command.mutationExpressions().operator(expression, node);
    if (operator == SqlScalarExpression.COLUMN) {
      return bindColumn(command, bound, expression, node, columnsAllowed);
    }
    if (operator == SqlScalarExpression.NULL) {
      int declared = command.mutationExpressions().descriptor(expression, node);
      boolean untyped = !SqlTypeDescriptor.isValid(declared);
      return push(
          bound, expression, operator, 0, 0,
          untyped ? SqlTypeDescriptor.BIGINT : declared, untyped);
    }
    if (SqlRowExpressionTypes.leaf(operator)) {
      int descriptor = command.mutationExpressions().descriptor(expression, node);
      return SqlTypeDescriptor.isValid(descriptor)
          ? push(
              bound,
              expression,
              operator,
              command.mutationExpressions().operandHigh(expression, node),
              command.mutationExpressions().operand(expression, node),
              descriptor,
              false)
          : StatusCode.DATATYPE_MISMATCH;
    }
    if (unary(operator)) return bindUnary(command, bound, expression, node);
    return binary(operator)
        ? bindBinary(command, bound, expression, node)
        : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode bindColumn(
      SqlCommand command,
      BoundSqlStatement bound,
      int expression,
      int node,
      boolean allowed) {
    if (!allowed) return StatusCode.FEATURE_NOT_SUPPORTED;
    int symbol = (int) command.mutationExpressions().operand(expression, node);
    CharSequence qualifier = command.projections().symbolTable(symbol);
    CharSequence name = command.projections().symbolName(symbol);
    if (qualifier == null || name == null
        || qualifier.length() > 0
            && !SqlBindingNames.matchesTable(command, qualifier)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = bound.table.findColumn(name);
    return column < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : push(
            bound,
            expression,
            SqlScalarExpression.COLUMN,
            0,
            column,
            bound.table.typeDescriptor(column),
            false);
  }

  private StatusCode bindUnary(
      SqlCommand command, BoundSqlStatement bound, int expression, int node) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = size - 1;
    int operator = command.mutationExpressions().operator(expression, node);
    int target = command.mutationExpressions().descriptor(expression, node);
    int descriptor = operator == SqlScalarExpression.CAST && untypedNulls[slot]
        ? target : SqlPostAggregateExpressionTypes.unary(
            operator,
            descriptors[slot],
            target,
            command.mutationExpressions().operand(expression, node));
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[slot] = descriptor;
    if (operator == SqlScalarExpression.CAST) untypedNulls[slot] = false;
    append(command, bound, expression, node, descriptor);
    return StatusCode.OK;
  }

  private StatusCode bindBinary(
      SqlCommand command, BoundSqlStatement bound, int expression, int node) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int right = --size;
    int left = size - 1;
    if (!resolveNulls(
        command.mutationExpressions().operator(expression, node), left, right)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int descriptor = SqlPostAggregateExpressionTypes.binary(
        command.mutationExpressions().operator(expression, node),
        descriptors[left],
        descriptors[right]);
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[left] = descriptor;
    append(command, bound, expression, node, descriptor);
    return StatusCode.OK;
  }

  private boolean resolveNulls(int operator, int left, int right) {
    if (!untypedNulls[left] && !untypedNulls[right]) return true;
    if (operator == SqlScalarExpression.ADD
        && SqlTypeDescriptor.typeId(descriptors[left])
            == SqlTypeDescriptor.TYPE_ID_DATE
        && untypedNulls[right]) {
      descriptors[right] = SqlTypeDescriptor.BIGINT;
      untypedNulls[right] = false;
      return true;
    }
    if (untypedNulls[left] == untypedNulls[right]) return false;
    int known = untypedNulls[right] ? descriptors[left] : descriptors[right];
    if (!SqlNumericTypeRules.isNumeric(known)) return false;
    descriptors[untypedNulls[right] ? right : left] = known;
    untypedNulls[left] = false;
    untypedNulls[right] = false;
    return true;
  }

  private StatusCode push(
      BoundSqlStatement bound,
      int expression,
      int operator,
      long operandHigh,
      long operand,
      int descriptor,
      boolean untyped) {
    if (size >= descriptors.length) return StatusCode.RESOURCE_EXHAUSTED;
    descriptors[size] = descriptor;
    untypedNulls[size++] = untyped;
    bound.projectionPrograms.appendMutation(
        expression, operator, operandHigh, operand, descriptor);
    return bound.projectionPrograms.status();
  }

  private static void append(
      SqlCommand command,
      BoundSqlStatement bound,
      int expression,
      int node,
      int descriptor) {
    bound.projectionPrograms.appendMutation(
        expression,
        command.mutationExpressions().operator(expression, node),
        command.mutationExpressions().operandHigh(expression, node),
        command.mutationExpressions().operand(expression, node),
        descriptor);
  }

  private static boolean unary(int operator) {
    return SqlRowExpressionTypes.unary(operator)
        || SqlExactExpressionEvaluator.unaryOperator(operator);
  }

  private static boolean binary(int operator) {
    return operator >= SqlScalarExpression.ADD
        && operator <= SqlScalarExpression.REMAINDER;
  }

  private static boolean fixedWidth(int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return type == SqlTypeDescriptor.TYPE_ID_BOOLEAN
        || SqlNumericTypeRules.isNumeric(descriptor)
        || SqlRowExpressionTypes.temporal(type);
  }
}
