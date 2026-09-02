package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Builds a bounded left-associative UNION tree while retaining parenthesized operands. */
final class SqlSetExpressionParser {
  private final SqlSetOperandParser operands;
  private final SqlSetTailParser tail = new SqlSetTailParser();
  private StatusCode failure;

  SqlSetExpressionParser(SqlParser parser) {
    operands = new SqlSetOperandParser(parser, this);
  }

  void parameterMarkers(SqlParameterMarkers markers) {
    operands.parameterMarkers(markers);
  }

  boolean contains(CharSequence sql, int start, int end) {
    return SqlSetExpressionScanner.contains(sql, start, end);
  }

  StatusCode parse(
      CharSequence sql, int start, int end, SqlQuery query, SqlCommand result) {
    failure = StatusCode.OK;
    int node = parseNode(sql, start, end, query, true);
    if (node < 0) return failure;
    StatusCode status = SqlSetExpressionValidation.validateArity(query);
    return status.isOk() ? query.publishSetResult(result) : status;
  }

  int parseNode(
      CharSequence sql, int start, int end, SqlQuery query, boolean root) {
    start = SqlSetExpressionScanner.skipSpaces(sql, start, end);
    end = SqlSetExpressionScanner.trimEnd(sql, start, end);
    if (start >= end) return fail(StatusCode.INVALID_EXTERNAL_INPUT);
    int firstUnion = SqlSetExpressionScanner.topLevel(sql, start, end, "UNION");
    if (firstUnion < 0) return operands.single(sql, start, end, query, root);
    int tailStart = SqlSetExpressionScanner.tail(sql, firstUnion + 5, end);
    int expressionEnd = tailStart < 0 ? end : tailStart;
    int left = operands.term(sql, start, firstUnion, query);
    if (left < 0) return left;
    int operator = firstUnion;
    while (operator >= 0) {
      int cursor = SqlSetExpressionScanner.skipSpaces(sql, operator + 5, expressionEnd);
      int kind = SqlQuery.SET_UNION_DISTINCT;
      if (SqlSetExpressionScanner.keyword(sql, cursor, expressionEnd, "ALL")) {
        kind = SqlQuery.SET_UNION_ALL;
        cursor = SqlSetExpressionScanner.skipSpaces(sql, cursor + 3, expressionEnd);
      } else if (SqlSetExpressionScanner.keyword(sql, cursor, expressionEnd, "DISTINCT")) {
        cursor = SqlSetExpressionScanner.skipSpaces(sql, cursor + 8, expressionEnd);
      }
      int next = SqlSetExpressionScanner.topLevel(sql, cursor, expressionEnd, "UNION");
      int right = operands.term(sql, cursor, next < 0 ? expressionEnd : next, query);
      if (right < 0) return right;
      left = query.appendSetUnion(kind, left, right);
      if (left < 0) return fail(StatusCode.QUERY_TOO_COMPLEX);
      operator = next;
    }
    if (tailStart >= 0) {
      if (!root) return fail(StatusCode.FEATURE_NOT_SUPPORTED);
      StatusCode status = tail.parse(sql, tailStart, end, query);
      if (!status.isOk()) return fail(status);
    }
    return left;
  }

  int fail(StatusCode status) { failure = status; return -1; }

  int parseRootTail(
      CharSequence sql, int start, int end, SqlQuery query, int node) {
    StatusCode status = tail.parse(sql, start, end, query);
    return status.isOk() ? node : fail(status);
  }
}
