package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses bounded row and aggregate SELECT items into one actual-count list. */
final class SqlSelectProjectionParser {
  private final SqlParserInput input;
  private final SqlSelectAggregateParser aggregates;
  private final SqlRowProjectionParser rows;

  SqlSelectProjectionParser(
      SqlSelectParser selects,
      SqlParserInput parserInput,
      SqlScalarExpressionParser expressions,
      SqlSelectAggregateParser aggregateParser) {
    input = parserInput;
    aggregates = aggregateParser;
    rows = new SqlRowProjectionParser(selects, expressions);
  }

  StatusCode parse(CharSequence sql, SqlCommand result, boolean distinct) {
    if (!distinct && input.consumeCharacter(sql, '*')) {
      result.setSelectAll();
      return StatusCode.OK;
    }
    StatusCode status = StatusCode.OK;
    do {
      int kind = distinct ? 0 : aggregateKind(sql);
      status = kind == 0
          ? rows.parse(sql, result) : aggregates.groupedList(sql, result, kind);
    } while (status.isOk() && input.consumeCharacter(sql, ','));
    return status;
  }

  private int aggregateKind(CharSequence sql) {
    if (input.consumeKeyword(sql, "COUNT")) return SqlAggregateKind.COUNT;
    if (input.consumeKeyword(sql, "SUM")) return SqlAggregateKind.SUM;
    if (input.consumeKeyword(sql, "AVG")) return SqlAggregateKind.AVG;
    if (input.consumeKeyword(sql, "MIN")) return SqlAggregateKind.MIN;
    if (input.consumeKeyword(sql, "MAX")) return SqlAggregateKind.MAX;
    return 0;
  }
}
