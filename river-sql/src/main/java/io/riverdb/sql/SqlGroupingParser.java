package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses and validates one actual-count GROUP BY expression tuple. */
final class SqlGroupingParser {
  private final SqlParserInput input;
  private final SqlAggregateExpressionParser expressions;
  private final SqlScalarExpression scratch = new SqlScalarExpression();

  SqlGroupingParser(
      SqlParserInput parserInput, SqlAggregateExpressionParser aggregateExpressions) {
    input = parserInput;
    expressions = aggregateExpressions;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    int outputs = command.columnCount() - command.aggregateOutputCount();
    StatusCode status;
    do {
      scratch.reset();
      status = expressions.parseScratch(sql, command, scratch);
      int projection = status.isOk() ? matchingProjection(command, outputs) : -1;
      if (status.isOk()) status = command.appendGroupExpression(projection, scratch);
    } while (status.isOk() && input.consumeCharacter(sql, ','));
    for (int output = 0; status.isOk() && output < outputs; output++) {
      if (!groupedProjection(command, output)) status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status;
  }

  private int matchingProjection(SqlCommand command, int outputs) {
    for (int projection = 0; projection < outputs; projection++) {
      if (SqlAggregateExpressionParser.same(
          command, command.projectionExpression(projection), scratch)) return projection;
    }
    return -1;
  }

  private static boolean groupedProjection(SqlCommand command, int projection) {
    return SqlGroupExpressions.groupKey(command, projection) >= 0;
  }
}
