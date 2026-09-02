package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Resolves one postfix program against a typed virtual child schema. */
final class SqlBlockExpressionBinder {
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] untypedNulls = new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private int size;
  private boolean generatedText;

  StatusCode bind(
      SqlCommand command,
      SqlScalarExpression expression,
      int lane,
      SqlBlockSchema child,
      BoundSqlStatement bound) {
    if (expression == null || !expression.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    size = 0;
    generatedText = false;
    for (int node = 0; node < expression.nodeCount(); node++) {
      StatusCode status = node(command, expression, lane, node, child, bound);
      if (!status.isOk()) return status;
    }
    StatusCode status = bound.projectionPrograms.status();
    if (!status.isOk()) return status;
    if (size != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int raw = expression.isDirectColumnReference()
        ? (int) bound.projectionPrograms.operand(lane, 0) : -1;
    if (raw < 0
        && SqlTypeDescriptor.typeId(descriptors[0]) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && !generatedText) return StatusCode.FEATURE_NOT_SUPPORTED;
    bound.projectionPrograms.finish(lane, descriptors[0], raw);
    return bound.projectionPrograms.status();
  }

  boolean nullable(
      SqlCommand command, SqlScalarExpression expression, SqlBlockSchema child) {
    for (int node = 0; node < expression.nodeCount(); node++) {
      int operator = expression.operator(node);
      if (operator == SqlScalarExpression.NULL) return true;
      if (operator != SqlScalarExpression.COLUMN) continue;
      int column = resolve(command, expression, node, child);
      if (column < 0 || child.nullable(column)) return true;
    }
    return false;
  }

  private StatusCode node(
      SqlCommand command,
      SqlScalarExpression expression,
      int lane,
      int node,
      SqlBlockSchema child,
      BoundSqlStatement bound) {
    int operator = expression.operator(node);
    if (operator == SqlScalarExpression.COLUMN) {
      int column = resolve(command, expression, node, child);
      return column < 0 ? StatusCode.INVALID_EXTERNAL_INPUT
          : push(bound, lane, operator, column, child.descriptor(column), false);
    }
    if (operator == SqlScalarExpression.NULL) {
      return push(bound, lane, operator, 0, SqlTypeDescriptor.BIGINT, true);
    }
    if (SqlRowExpressionTypes.leaf(operator)) {
      int descriptor = expression.typeDescriptor(node);
      return SqlTypeDescriptor.isValid(descriptor)
          ? push(bound, lane, operator, expression.operand(node), descriptor, false)
          : StatusCode.DATATYPE_MISMATCH;
    }
    if (SqlRowExpressionTypes.unary(operator)
        || SqlExactExpressionEvaluator.unaryOperator(operator)) {
      return unary(expression, lane, node, bound);
    }
    return operator >= SqlScalarExpression.ADD
            && operator <= SqlScalarExpression.REMAINDER
        ? binary(expression, lane, node, bound) : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode unary(
      SqlScalarExpression expression, int lane, int node, BoundSqlStatement bound) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = size - 1;
    int operator = expression.operator(node);
    int target = expression.typeDescriptor(node);
    generatedText = operator == SqlScalarExpression.CAST
        && SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && (untypedNulls[slot]
            || SqlRowExpressionTypes.temporal(SqlTypeDescriptor.typeId(descriptors[slot])));
    int descriptor = operator == SqlScalarExpression.CAST && untypedNulls[slot]
        ? target : SqlPostAggregateExpressionTypes.unary(
            operator, descriptors[slot], target, expression.operand(node));
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[slot] = descriptor;
    if (operator == SqlScalarExpression.CAST) untypedNulls[slot] = false;
    bound.projectionPrograms.append(lane, operator, expression.operand(node), descriptor);
    return bound.projectionPrograms.status();
  }

  private StatusCode binary(
      SqlScalarExpression expression, int lane, int node, BoundSqlStatement bound) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int right = --size;
    int left = size - 1;
    if (!resolveNulls(expression.operator(node), left, right)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int descriptor = SqlPostAggregateExpressionTypes.binary(
        expression.operator(node), descriptors[left], descriptors[right]);
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[left] = descriptor;
    bound.projectionPrograms.append(
        lane, expression.operator(node), expression.operand(node), descriptor);
    return bound.projectionPrograms.status();
  }

  private boolean resolveNulls(int operator, int left, int right) {
    if (!untypedNulls[left] && !untypedNulls[right]) return true;
    if (operator == SqlScalarExpression.ADD
        && SqlTypeDescriptor.typeId(descriptors[left]) == SqlTypeDescriptor.TYPE_ID_DATE
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
      int lane,
      int operator,
      long operand,
      int descriptor,
      boolean untyped) {
    if (size >= descriptors.length) return StatusCode.RESOURCE_EXHAUSTED;
    bound.projectionPrograms.append(lane, operator, operand, descriptor);
    StatusCode status = bound.projectionPrograms.status();
    if (!status.isOk()) return status;
    descriptors[size] = descriptor;
    untypedNulls[size++] = untyped;
    return StatusCode.OK;
  }

  private static int resolve(
      SqlCommand command,
      SqlScalarExpression expression,
      int node,
      SqlBlockSchema child) {
    int symbol = (int) expression.operand(node);
    CharSequence name = command == null ? null : command.projectionSymbolName(symbol);
    if (name == null) return -1;
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (qualifier == null || qualifier.length() > 0
        && !SqlBindingNames.matchesTable(command, qualifier)) return -1;
    return child.find(name);
  }
}
