package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses the one visible aggregate invocation and registers its deduplicated slot. */
final class SqlAggregateInvocationParser {
  private final SqlParserInput input;
  private final SqlSelectParser selects;
  private final SqlAggregateExpressionParser expressions;

  SqlAggregateInvocationParser(
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
    StatusCode status = input.requireCharacter(sql, '(');
    boolean countStar = requestedKind == SqlAggregateKind.COUNT
        && status.isOk() && input.consumeCharacter(sql, '*');
    int kind = countStar ? SqlAggregateKind.COUNT
        : requestedKind == SqlAggregateKind.COUNT
            ? SqlAggregateKind.COUNT_VALUE : requestedKind;
    if (status.isOk() && countStar) status = appendCountOutput(command);
    else if (status.isOk()) status = expressions.parse(sql, command);
    if (status.isOk()) status = input.requireCharacter(sql, ')');
    if (status.isOk()) status = selects.optionalColumnAlias(sql, command, output);
    if (!status.isOk()) return status;
    int invocation = command.appendAggregateInvocation(
        kind, countStar ? -1 : output);
    if (invocation < 0 || !command.appendAggregateOutput(invocation)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    command.set(routeType(kind, grouped), 0, 0);
    return StatusCode.OK;
  }

  private static StatusCode appendCountOutput(SqlCommand command) {
    SqlIdentifier output = command.writableNextColumnName();
    if (output == null) return StatusCode.RESOURCE_EXHAUSTED;
    String name = "count";
    for (int index = 0; index < name.length(); index++) output.append(name.charAt(index));
    return StatusCode.OK;
  }

  private static SqlCommandType routeType(int kind, boolean grouped) {
    return switch (kind) {
      case SqlAggregateKind.COUNT -> grouped
          ? SqlCommandType.GROUP_COUNT : SqlCommandType.COUNT;
      case SqlAggregateKind.COUNT_VALUE -> grouped
          ? SqlCommandType.GROUP_COUNT_VALUE : SqlCommandType.COUNT_VALUE;
      case SqlAggregateKind.SUM -> grouped
          ? SqlCommandType.GROUP_SUM : SqlCommandType.SUM;
      case SqlAggregateKind.AVG -> grouped
          ? SqlCommandType.GROUP_AVG : SqlCommandType.AVG;
      case SqlAggregateKind.MIN -> grouped
          ? SqlCommandType.GROUP_MIN : SqlCommandType.MIN;
      case SqlAggregateKind.MAX -> grouped
          ? SqlCommandType.GROUP_MAX : SqlCommandType.MAX;
      default -> null;
    };
  }
}
