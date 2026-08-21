package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;

/** Binds the single child-local projection of one predicate subquery. */
final class SqlNestedProjectionBinder {
  private final int[] types = new int[SqlScalarExpression.MAXIMUM_NODES];
  private final boolean[] nulls = new boolean[SqlScalarExpression.MAXIMUM_NODES];
  private int size;
  private boolean generatedText;

  StatusCode bind(
      SqlCommand command,
      TableDefinition table,
      SqlBoundProjectionPrograms target,
      BoundSqlQuery.Block block) {
    SqlScalarExpression expression = command.projectionExpression(0);
    if (expression == null || !expression.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.begin(1);
    size = 0;
    generatedText = false;
    for (int node = 0; node < expression.nodeCount(); node++) {
      StatusCode status = node(command, table, target, expression, node);
      if (!status.isOk()) {
        target.reset();
        return status;
      }
    }
    if (size != 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int raw = expression.isDirectColumnReference() ? (int) target.operand(0, 0) : -1;
    if (types[0] != 0 && raw < 0 && text(types[0]) && !generatedText) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    target.finish(0, types[0], raw);
    block.setProjection(
        raw < 0 ? SqlBoundProjectionPrograms.COMPUTED_PROJECTION : raw, types[0]);
    return StatusCode.OK;
  }

  private StatusCode node(
      SqlCommand command,
      TableDefinition table,
      SqlBoundProjectionPrograms target,
      SqlScalarExpression expression,
      int node) {
    int operator = expression.operator(node);
    if (operator == SqlScalarExpression.COLUMN) {
      return column(command, table, target, expression, node);
    }
    if (operator == SqlScalarExpression.NULL) {
      return push(target, operator, 0, 0, true);
    }
    if (SqlRowExpressionTypes.leaf(operator)) {
      int descriptor = expression.typeDescriptor(node);
      if (text(descriptor)) generatedText = true;
      return SqlTypeDescriptor.isValid(descriptor)
          ? push(target, operator, expression.operand(node), descriptor, false)
          : StatusCode.DATATYPE_MISMATCH;
    }
    if (SqlRowExpressionTypes.unary(operator)
        || SqlExactExpressionEvaluator.unaryOperator(operator)) {
      return unary(target, expression, node);
    }
    return operator >= SqlScalarExpression.ADD && operator <= SqlScalarExpression.REMAINDER
        ? binary(target, expression, node) : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private StatusCode column(
      SqlCommand command,
      TableDefinition table,
      SqlBoundProjectionPrograms target,
      SqlScalarExpression expression,
      int node) {
    int symbol = (int) expression.operand(node);
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    CharSequence name = command.projectionSymbolName(symbol);
    if (qualifier == null || name == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (qualifier.length() > 0 && !SqlBindingNames.matchesTable(command, qualifier)) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    int column = table.findColumn(name);
    return column < 0 ? StatusCode.INVALID_EXTERNAL_INPUT
        : push(target, SqlScalarExpression.COLUMN, column, table.typeDescriptor(column), false);
  }

  private StatusCode unary(
      SqlBoundProjectionPrograms target, SqlScalarExpression expression, int node) {
    if (size < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int slot = size - 1;
    int operator = expression.operator(node);
    int requested = expression.typeDescriptor(node);
    generatedText = operator == SqlScalarExpression.CAST
        && text(requested)
        && (nulls[slot]
            || SqlRowExpressionTypes.temporal(SqlTypeDescriptor.typeId(types[slot])));
    int descriptor = operator == SqlScalarExpression.CAST && nulls[slot]
        ? requested : SqlPostAggregateExpressionTypes.unary(
            operator, types[slot], requested, expression.operand(node));
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    types[slot] = descriptor;
    if (operator == SqlScalarExpression.CAST) nulls[slot] = false;
    target.append(0, operator, expression.operand(node), descriptor);
    return StatusCode.OK;
  }

  private StatusCode binary(
      SqlBoundProjectionPrograms target, SqlScalarExpression expression, int node) {
    if (size < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int operator = expression.operator(node);
    int right = types[--size];
    if (nulls[size] == nulls[size - 1]) {
      if (nulls[size]) return StatusCode.DATATYPE_MISMATCH;
    } else {
      int known = nulls[size] ? types[size - 1] : types[size];
      if (SqlTypeDescriptor.comparisonFamily(known)
          != SqlTypeDescriptor.COMPARISON_EXACT_NUMERIC) {
        if (!(operator == SqlScalarExpression.ADD && nulls[size]
            && SqlTypeDescriptor.typeId(types[size - 1])
                == SqlTypeDescriptor.TYPE_ID_DATE)) return StatusCode.DATATYPE_MISMATCH;
        right = SqlTypeDescriptor.BIGINT;
      }
      nulls[size] = false;
      nulls[size - 1] = false;
    }
    int descriptor = SqlPostAggregateExpressionTypes.binary(
        operator, types[size - 1], right);
    if (descriptor == 0) return StatusCode.DATATYPE_MISMATCH;
    types[size - 1] = descriptor;
    target.append(0, operator, expression.operand(node), descriptor);
    return StatusCode.OK;
  }

  private StatusCode push(
      SqlBoundProjectionPrograms target,
      int operator,
      long operand,
      int descriptor,
      boolean nullValue) {
    if (size >= types.length) return StatusCode.RESOURCE_EXHAUSTED;
    types[size] = descriptor;
    nulls[size++] = nullValue;
    target.append(0, operator, operand, descriptor);
    return StatusCode.OK;
  }

  private static boolean text(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
