package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds one bounded postfix row program using reusable primitive stacks. */
final class SqlRowProjectionProgramBinder {
  private final int[] descriptors = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] untypedNulls =
      new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private int size;
  private boolean generatedTemporalText;

  StatusCode bind(SqlCommand command, BoundSqlStatement bound, int projection) {
    SqlScalarExpression expression = command.projectionExpression(projection);
    StatusCode status = bindProgram(command, bound, expression, projection);
    return status.isOk() ? publishProjection(bound, expression, projection) : status;
  }

  StatusCode bindAggregateOperand(
      SqlCommand command, BoundSqlStatement bound, int projection) {
    SqlScalarExpression expression = command.aggregateOperandExpression(projection);
    return bindProgram(command, bound, expression, projection);
  }

  private StatusCode bindProgram(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlScalarExpression expression,
      int program) {
    if (expression == null || !expression.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    size = 0;
    generatedTemporalText = false;
    for (int node = 0; node < expression.nodeCount(); node++) {
      StatusCode status = bindNode(command, bound, expression, program, node);
      if (!status.isOk()) return status;
    }
    return finishProgram(bound, expression, program);
  }

  private StatusCode bindNode(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlScalarExpression expression,
      int projection,
      int node) {
    int operator = expression.operator(node);
    if (operator == SqlScalarExpression.COLUMN) {
      return bindColumn(command, bound, expression, projection, node);
    }
    if (operator == SqlScalarExpression.NULL) {
      return push(bound, projection, operator, 0, SqlTypeDescriptor.BIGINT, true);
    }
    if (SqlRowExpressionTypes.leaf(operator)) {
      int descriptor = expression.typeDescriptor(node);
      return SqlTypeDescriptor.isValid(descriptor)
          ? push(bound, projection, operator, expression.operand(node), descriptor, false)
          : StatusCode.DATATYPE_MISMATCH;
    }
    if (SqlRowExpressionTypes.unary(operator)
        || SqlExactExpressionEvaluator.unaryOperator(operator)) {
      return bindUnary(bound, expression, projection, node);
    }
    return operator >= SqlScalarExpression.ADD
            && operator <= SqlScalarExpression.REMAINDER
        ? bindArithmetic(bound, expression, projection, node)
        : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode bindColumn(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlScalarExpression expression,
      int projection,
      int node) {
    int column = resolveSymbol(command, bound, (int) expression.operand(node));
    return column < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : push(
            bound,
            projection,
            SqlScalarExpression.COLUMN,
            column,
            bound.table.typeDescriptor(column),
            false);
  }

  private StatusCode bindUnary(
      BoundSqlStatement bound,
      SqlScalarExpression expression,
      int projection,
      int node) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = size - 1;
    int operator = expression.operator(node);
    int target = expression.typeDescriptor(node);
    generatedTemporalText = operator == SqlScalarExpression.CAST
        && SqlTypeDescriptor.typeId(target) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && (untypedNulls[slot]
            || SqlRowExpressionTypes.temporal(SqlTypeDescriptor.typeId(descriptors[slot])));
    int descriptor = operator == SqlScalarExpression.CAST && untypedNulls[slot]
        ? target
        : SqlPostAggregateExpressionTypes.unary(
            operator, descriptors[slot], target, expression.operand(node));
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[slot] = descriptor;
    if (operator == SqlScalarExpression.CAST) untypedNulls[slot] = false;
    bound.projectionPrograms.append(
        projection, operator, expression.operand(node), descriptor);
    return StatusCode.OK;
  }

  private StatusCode bindArithmetic(
      BoundSqlStatement bound,
      SqlScalarExpression expression,
      int projection,
      int node) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int operator = expression.operator(node);
    int right = descriptors[--size];
    if (untypedDateAdd(operator)) {
      right = SqlTypeDescriptor.BIGINT;
      untypedNulls[size] = false;
    } else if (untypedNumeric()) {
      untypedNulls[size] = false;
      untypedNulls[size - 1] = false;
    } else if (untypedNulls[size] || untypedNulls[size - 1]) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    int descriptor = SqlPostAggregateExpressionTypes.binary(
        operator, descriptors[size - 1], right);
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    descriptors[size - 1] = descriptor;
    bound.projectionPrograms.append(
        projection, operator, expression.operand(node), descriptor);
    return StatusCode.OK;
  }

  private boolean untypedDateAdd(int operator) {
    return operator == SqlScalarExpression.ADD
        && SqlTypeDescriptor.typeId(descriptors[size - 1])
            == SqlTypeDescriptor.TYPE_ID_DATE
        && untypedNulls[size];
  }

  private boolean untypedNumeric() {
    boolean rightNull = untypedNulls[size];
    boolean leftNull = untypedNulls[size - 1];
    if (rightNull == leftNull) return false;
    int known = rightNull ? descriptors[size - 1] : descriptors[size];
    return SqlTypeDescriptor.comparisonFamily(known)
        == SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC;
  }

  private StatusCode push(
      BoundSqlStatement bound,
      int projection,
      int operator,
      long operand,
      int descriptor,
      boolean untypedNull) {
    if (size >= descriptors.length) return StatusCode.RESOURCE_EXHAUSTED;
    descriptors[size] = descriptor;
    untypedNulls[size] = untypedNull;
    size++;
    bound.projectionPrograms.append(
        projection, operator, operand, descriptor);
    return StatusCode.OK;
  }

  private StatusCode finishProgram(
      BoundSqlStatement bound, SqlScalarExpression expression, int projection) {
    if (size != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int raw = expression.isDirectColumnReference()
        ? (int) bound.projectionPrograms.operand(projection, 0) : -1;
    if (raw < 0
        && SqlTypeDescriptor.typeId(descriptors[0])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR
        && !generatedTemporalText) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    bound.projectionPrograms.finish(projection, descriptors[0], raw);
    return StatusCode.OK;
  }

  private static StatusCode publishProjection(
      BoundSqlStatement bound, SqlScalarExpression expression, int projection) {
    int raw = bound.projectionPrograms.rawColumn(projection);
    int column = expression.isNullLiteral()
        ? BoundSqlStatement.NULL_PROJECTION
        : raw >= 0 ? raw : SqlBoundProjectionPrograms.COMPUTED_PROJECTION;
    bound.projectedColumns[projection] = column;
    return duplicate(bound, projection, column)
        ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
  }

  private static int resolveSymbol(
      SqlCommand command, BoundSqlStatement bound, int symbol) {
    CharSequence name = command.projectionSymbolName(symbol);
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (name == null || qualifier == null
        || qualifier.length() > 0
            && !SqlBindingNames.matchesTable(command, qualifier)) {
      return -1;
    }
    return bound.table.findColumn(name);
  }

  private static boolean duplicate(
      BoundSqlStatement bound, int projection, int column) {
    if (column == SqlBoundProjectionPrograms.COMPUTED_PROJECTION) return false;
    for (int previous = 0; previous < projection; previous++) {
      if (bound.projectedColumns[previous] == column) return true;
    }
    return false;
  }
}
