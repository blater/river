package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one visible aggregate invocation and registers its reusable slot. */
final class SqlAggregateInvocationParser {
  private final SqlAggregateInvocationBody body;

  SqlAggregateInvocationParser(
      SqlParserInput parserInput,
      SqlSelectParser selectParser,
      SqlAggregateExpressionParser aggregateExpressions) {
    body = new SqlAggregateInvocationBody(
        parserInput, selectParser, aggregateExpressions);
  }

  StatusCode parse(
      CharSequence sql, SqlCommand command, int requestedKind, boolean grouped) {
    return body.parse(sql, command, requestedKind, grouped);
  }
}
