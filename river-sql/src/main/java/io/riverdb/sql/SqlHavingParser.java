package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses HAVING through the common bounded Boolean predicate grammar. */
final class SqlHavingParser {
  private final SqlBooleanWhereParser predicates;

  SqlHavingParser(
      SqlParser parent,
      SqlParserInput parserInput,
      SqlAggregateExpressionParser selected) {
    predicates = new SqlBooleanWhereParser(parserInput, selected);
  }

  StatusCode parse(CharSequence sql, SqlCommand command, boolean grouped) {
    return predicates.parseHaving(sql, command, grouped);
  }
}
