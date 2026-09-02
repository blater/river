package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Retains the resolved column-reference set of one table CHECK comparison. */
final class SqlTableCheckConstraintParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlScalarExpressionParser expressions;
  private final SqlScalarExpression left = new SqlScalarExpression();
  private final SqlScalarExpression right = new SqlScalarExpression();

  SqlTableCheckConstraintParser(
      SqlParser parent, SqlParserInput source, SqlScalarExpressionParser scalarExpressions) {
    parser = parent; input = source; expressions = scalarExpressions;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (status.isOk()) status = expressions.parseProjectionScratch(sql, command, left);
    SqlComparison comparison = status.isOk() ? parser.comparisonOperator(sql) : null;
    if (status.isOk() && (comparison == null || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (status.isOk()) status = expressions.parseProjectionScratch(sql, command, right);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    if (status.isOk()) status = addReferences(command, left);
    return status.isOk() ? addReferences(command, right) : status;
  }

  private static StatusCode addReferences(SqlCommand command, SqlScalarExpression expression) {
    StatusCode status = StatusCode.OK;
    for (int node = 0; status.isOk() && node < expression.nodeCount(); node++) {
      if (expression.operator(node) != SqlScalarExpression.COLUMN) continue;
      int symbol = (int) expression.operand(node);
      SqlIdentifier table = command.projectionSymbolTable(symbol);
      SqlIdentifier name = command.projectionSymbolName(symbol);
      status = table == null || table.length() != 0 || name == null
          ? StatusCode.INVALID_EXTERNAL_INPUT : command.addTableConstraintPart(name, null);
    }
    return status;
  }
}
