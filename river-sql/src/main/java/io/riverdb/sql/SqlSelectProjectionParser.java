package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses bounded row projections and the two-column grouped aggregate shape. */
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
    StatusCode status = rows.parse(sql, result);
    if (distinct || !status.isOk() || !input.consumeCharacter(sql, ',')) {
      return status;
    }
    int kind = aggregateKind(sql);
    if (kind != 0) return aggregates.groupedList(sql, result, kind);
    status = rows.parse(sql, result);
    while (status.isOk() && input.consumeCharacter(sql, ',')) {
      status = rows.parse(sql, result);
    }
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
