package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses and registers one visible aggregate invocation. */
final class SqlAggregateInvocationBody {
  private final SqlParserInput input;
  private final SqlSelectParser selects;
  private final SqlAggregateExpressionParser expressions;

  SqlAggregateInvocationBody(
      SqlParserInput parserInput,
      SqlSelectParser selectParser,
      SqlAggregateExpressionParser aggregateExpressions) {
    input = parserInput;
    selects = selectParser;
    expressions = aggregateExpressions;
  }

  StatusCode parse(
      CharSequence sql, SqlCommand command, int requestedKind, boolean grouped) {
    int output = command.columnCount();
    boolean first = command.aggregateInvocationCount() == 0;
    StatusCode status = input.requireCharacter(sql, '(');
    int kind = status.isOk()
        ? SqlAggregateInvocationKind.consume(input, sql, requestedKind)
        : requestedKind;
    if (status.isOk()) {
      status = kind == SqlAggregateKind.COUNT
          ? SqlAggregateInvocationKind.appendCountOutput(command)
          : expressions.parse(sql, command);
    }
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    if (status.isOk()) status = selects.optionalColumnAlias(sql, command, output);
    if (!status.isOk()) return status;
    return SqlAggregateInvocationRegistration.append(
        command, kind, kind == SqlAggregateKind.COUNT ? -1 : output,
        grouped, first);
  }
}
