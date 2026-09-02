package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses SELECT leaves and delegates parenthesized set expressions to their owner. */
final class SqlSetOperandParser {
  private final SqlParser statements;
  private final SqlSetExpressionParser expressions;
  private final SqlSetQuerySource source = new SqlSetQuerySource();

  SqlSetOperandParser(SqlParser parser, SqlSetExpressionParser owner) {
    statements = parser;
    expressions = owner;
  }

  void parameterMarkers(SqlParameterMarkers markers) {
    source.parameterMarkers(markers);
  }

  int single(CharSequence sql, int start, int end, SqlQuery query, boolean root) {
    if (sql.charAt(start) != '(') return leaf(sql, start, end, query);
    int close = SqlNestedSubqueryScanner.matchingClose(sql, start, end);
    if (close < 0) return expressions.fail(StatusCode.INVALID_EXTERNAL_INPUT);
    int remainder = SqlSetExpressionScanner.skipSpaces(sql, close + 1, end);
    if (remainder == end) return expressions.parseNode(sql, start + 1, close, query, root);
    if (!root || !tailStarts(sql, remainder, end)) {
      return expressions.fail(StatusCode.INVALID_EXTERNAL_INPUT);
    }
    int node = expressions.parseNode(sql, start + 1, close, query, false);
    if (node < 0) return node;
    return expressions.parseRootTail(sql, remainder, end, query, node);
  }

  int term(CharSequence sql, int start, int end, SqlQuery query) {
    start = SqlSetExpressionScanner.skipSpaces(sql, start, end);
    end = SqlSetExpressionScanner.trimEnd(sql, start, end);
    if (start >= end) return expressions.fail(StatusCode.INVALID_EXTERNAL_INPUT);
    if (sql.charAt(start) != '(') return leaf(sql, start, end, query);
    int close = SqlNestedSubqueryScanner.matchingClose(sql, start, end);
    return close == end - 1
        ? expressions.parseNode(sql, start + 1, close, query, false)
        : expressions.fail(StatusCode.INVALID_EXTERNAL_INPUT);
  }

  private int leaf(CharSequence sql, int start, int end, SqlQuery query) {
    int block = query.blockCount();
    source.set(sql, start, end);
    StatusCode status = statements.parseSetOperand(source, query);
    if (!status.isOk()) return expressions.fail(status);
    SqlCommand command = query.block(block);
    if (command.isSelectForUpdate()) return expressions.fail(StatusCode.FEATURE_NOT_SUPPORTED);
    int node = query.appendSetLeaf(block);
    if (node < 0) return expressions.fail(StatusCode.QUERY_TOO_COMPLEX);
    status = query.finishSetLeaf(node);
    return status.isOk() ? node : expressions.fail(status);
  }

  private static boolean tailStarts(CharSequence sql, int start, int end) {
    return SqlSetExpressionScanner.keyword(sql, start, end, "ORDER")
        || SqlSetExpressionScanner.keyword(sql, start, end, "LIMIT");
  }
}
