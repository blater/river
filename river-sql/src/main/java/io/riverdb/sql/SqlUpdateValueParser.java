package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one bounded UPDATE assignment value into the command-owned primitive carrier. */
final class SqlUpdateValueParser {
  private final SqlParserInput input;
  private final SqlScalarExpressionParser expressions;
  private final SqlScalarExpression scratch = new SqlScalarExpression();

  SqlUpdateValueParser(
      SqlParserInput parserInput, SqlScalarExpressionParser expressionParser) {
    input = parserInput;
    expressions = expressionParser;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    boolean defaultValue = input.consumeKeyword(sql, "DEFAULT");
    if (defaultValue) {
      command.appendUpdate(
          0, 0, false, true, 0, SqlCommand.UPDATE_LITERAL);
      return StatusCode.OK;
    }
    StatusCode status = parseValue(sql, command);
    if (!status.isOk()) return status;
    if (directValue(scratch)) {
      boolean nullValue = scratch.operator(0) == SqlScalarExpression.NULL;
      boolean parameter = scratch.operator(0) == SqlScalarExpression.PARAMETER;
      command.appendUpdate(
          nullValue ? 0 : scratch.operandHigh(0),
          nullValue ? 0 : scratch.operand(0),
          nullValue,
          false,
          parameter ? SqlCommand.mutationParameterDescriptor() : scratch.typeDescriptor(0),
          parameter ? SqlCommand.UPDATE_PARAMETER : SqlCommand.UPDATE_LITERAL);
      return StatusCode.OK;
    }
    int expression = command.appendMutationExpression(scratch);
    if (expression < 0) return StatusCode.RESOURCE_EXHAUSTED;
    command.appendUpdate(
        0, expression, false, false, 0, SqlCommand.UPDATE_EXPRESSION);
    return StatusCode.OK;
  }

  StatusCode parseInsert(
      CharSequence sql, SqlCommand command, SqlParser.LongResult result) {
    StatusCode status = parseValue(sql, command);
    if (!status.isOk()) return status;
    if (directValue(scratch)) {
      result.nullValue = scratch.operator(0) == SqlScalarExpression.NULL;
      result.value = result.nullValue ? 0 : scratch.operand(0);
      result.high = result.nullValue ? 0 : scratch.operandHigh(0);
      result.typeDescriptor = scratch.operator(0) == SqlScalarExpression.PARAMETER
          ? SqlCommand.mutationParameterDescriptor() : scratch.typeDescriptor(0);
      return StatusCode.OK;
    }
    int expression = command.appendMutationExpression(scratch);
    if (expression < 0) return StatusCode.RESOURCE_EXHAUSTED;
    result.nullValue = false;
    result.high = 0;
    result.value = expression;
    result.typeDescriptor = SqlCommand.mutationExpressionDescriptor();
    return StatusCode.OK;
  }

  private static boolean directValue(SqlScalarExpression expression) {
    return expression.nodeCount() == 1
        && (expression.operator(0) == SqlScalarExpression.LITERAL
            || expression.operator(0) == SqlScalarExpression.NULL
            || expression.operator(0) == SqlScalarExpression.PARAMETER);
  }

  private StatusCode parseValue(CharSequence sql, SqlCommand command) {
    return expressions.parseMutation(sql, command, scratch);
  }
}
