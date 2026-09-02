package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one deterministic owner-column CHECK expression and literal comparison. */
final class SqlColumnCheckParser {
  private final SqlParserInput input;
  private final SqlParser parser;
  private final SqlScalarExpressionParser expressions;
  private final SqlParser.LongResult literal = new SqlParser.LongResult();

  SqlColumnCheckParser(
      SqlParser parent,
      SqlParserInput parserInput,
      SqlScalarExpressionParser expressionParser) {
    parser = parent;
    input = parserInput;
    expressions = expressionParser;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    StatusCode status = input.requireCharacter(sql, '(');
    if (!status.isOk()) return status;
    int column = command.columnCount() - 1;
    status = expressions.parseProjection(sql, command, column);
    if (!status.isOk()) return status;
    SqlScalarExpression expression = command.projectionExpression(column);
    status = syntax(command, column, expression);
    if (!status.isOk()) return status;
    SqlComparison comparison = parser.comparisonOperator(sql);
    if (comparison == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN
        || comparison == SqlComparison.NOT_IN) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    status = input.literal(sql, literal);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    if (status.isOk()) {
      command.markLastColumnCheck(
          comparison, literal.high, literal.value, literal.typeDescriptor);
    }
    return status;
  }

  private static StatusCode syntax(
      SqlCommand command, int owner, SqlScalarExpression expression) {
    boolean hasOwner = false;
    for (int node = 0; node < expression.nodeCount(); node++) {
      int operator = expression.operator(node);
      if (operator == SqlScalarExpression.COLUMN) {
        int symbol = (int) expression.operand(node);
        SqlIdentifier table = command.projectionSymbolTable(symbol);
        SqlIdentifier name = command.projectionSymbolName(symbol);
        if (table == null || table.length() != 0 || name == null
            || !sameIdentifier(name, command.columnName(owner))) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        hasOwner = true;
      } else if (!allowed(operator)) {
        return StatusCode.FEATURE_NOT_SUPPORTED;
      }
    }
    return hasOwner ? StatusCode.OK : StatusCode.FEATURE_NOT_SUPPORTED;
  }

  private static boolean allowed(int operator) {
    return operator == SqlScalarExpression.LITERAL
        || operator == SqlScalarExpression.ADD
        || operator == SqlScalarExpression.SUBTRACT
        || operator == SqlScalarExpression.CAST
        || operator == SqlScalarExpression.EXTRACT;
  }

  private static boolean sameIdentifier(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) return false;
    }
    return true;
  }
}
