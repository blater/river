package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses GROUP BY/HAVING or validates a scalar aggregate projection tail. */
final class SqlAggregateGroupTail {
  private final SqlParserInput input;
  private final SqlGroupingParser grouping;
  private final SqlHavingParser having;

  SqlAggregateGroupTail(
      SqlParserInput parserInput,
      SqlGroupingParser groupingParser,
      SqlHavingParser havingParser) {
    input = parserInput;
    grouping = groupingParser;
    having = havingParser;
  }

  StatusCode parse(CharSequence sql, SqlCommand command) {
    if (input.consumeKeyword(sql, "GROUP")) return grouped(sql, command);
    if (command.aggregateInvocationCount() == 0) return StatusCode.OK;
    if (command.columnCount() != command.aggregateOutputCount()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return input.consumeKeyword(sql, "HAVING")
        ? having.parse(sql, command, false) : StatusCode.OK;
  }

  private StatusCode grouped(CharSequence sql, SqlCommand command) {
    StatusCode status = input.requireKeyword(sql, "BY");
    if (status.isOk()) status = grouping.parse(sql, command);
    if (status.isOk()) command.set(
        command.aggregateInvocationCount() == 0
            ? SqlCommandType.DISTINCT_SCAN
            : SqlAggregateCommandType.grouped(command.type()),
        0,
        0);
    if (status.isOk() && input.consumeKeyword(sql, "HAVING")) {
      status = having.parse(sql, command, true);
    }
    return status;
  }
}
