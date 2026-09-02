package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses aggregate projections, sources, and bounded HAVING comparisons. */
final class SqlSelectAggregateParser {
  private final SqlParser parser;
  private final SqlParserInput input;
  private final SqlAggregateExpressionParser expressions;
  private final SqlAggregateInvocationParser invocations;
  private final SqlHavingParser having;
  private final SqlAggregateGroupTail groupTail;

  SqlSelectAggregateParser(
      SqlParser parent,
      SqlSelectParser selectParser,
      SqlParserInput parserInput,
      SqlScalarExpressionParser expressions) {
    parser = parent;
    input = parserInput;
    this.expressions = new SqlAggregateExpressionParser(expressions);
    invocations = new SqlAggregateInvocationParser(
        parserInput, selectParser, this.expressions);
    having = new SqlHavingParser(parent, parserInput, this.expressions);
    SqlGroupingParser grouping = new SqlGroupingParser(parserInput, this.expressions);
    groupTail = new SqlAggregateGroupTail(parserInput, grouping, having);
  }

  StatusCode scalarList(
      CharSequence sql, SqlCommand result, int firstKind) {
    StatusCode status = invocations.parse(sql, result, firstKind, false);
    return status.isOk() ? aggregateSource(sql, result) : status;
  }

  StatusCode groupedList(
      CharSequence sql, SqlCommand result, int firstKind) {
    StatusCode status = invocations.parse(sql, result, firstKind, false);
    return status;
  }

  StatusCode aggregateSource(CharSequence sql, SqlCommand result) {
    StatusCode status = requireKeyword(sql, "FROM");
    if (status.isOk()) {
      status = identifier(sql, result.writableTableName());
    }
    if (status.isOk()) {
      status = optionalTableAlias(sql, result);
    }
    if (status.isOk() && consumeKeyword(sql, "WHERE")) {
      status = predicates(sql, result, false);
    }
    if (status.isOk() && consumeKeyword(sql, "HAVING")) {
      status = having.parse(sql, result, false);
    }
    return status;
  }

  StatusCode finishGroupOrPoint(CharSequence sql, SqlCommand result) {
    return groupTail.parse(sql, result);
  }

  private StatusCode predicates(
      CharSequence sql, SqlCommand result, boolean qualified) {
    return parser.predicates(sql, result, qualified);
  }

  private StatusCode optionalTableAlias(CharSequence sql, SqlCommand result) {
    return parser.optionalTableAlias(sql, result);
  }

  private StatusCode identifier(CharSequence sql, SqlIdentifier result) {
    return input.identifier(sql, result);
  }

  private StatusCode requireKeyword(CharSequence sql, String keyword) {
    return input.requireKeyword(sql, keyword);
  }

  private boolean consumeKeyword(CharSequence sql, String keyword) {
    return input.consumeKeyword(sql, keyword);
  }

}
