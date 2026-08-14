package io.riverdb.sql;

/** Parses SQL comparison operators for predicate and schema-check grammars. */
final class SqlComparisonParser {
  private final SqlParserInput input;

  SqlComparisonParser(SqlParserInput parserInput) {
    input = parserInput;
  }

  SqlComparison parse(CharSequence sql) {
    if (input.consumeKeyword(sql, "NOT")) {
      return input.consumeKeyword(sql, "IN") ? SqlComparison.NOT_IN : null;
    }
    if (input.consumeKeyword(sql, "IN")) {
      return SqlComparison.IN;
    }
    if (input.consumeCharacter(sql, '=')) {
      return SqlComparison.EQUAL;
    }
    if (input.consumeCharacter(sql, '!')) {
      return input.consumeCharacter(sql, '=') ? SqlComparison.NOT_EQUAL : null;
    }
    if (input.consumeCharacter(sql, '<')) {
      if (input.consumeCharacter(sql, '>')) {
        return SqlComparison.NOT_EQUAL;
      }
      return input.consumeCharacter(sql, '=')
          ? SqlComparison.LESS_OR_EQUAL : SqlComparison.LESS_THAN;
    }
    if (input.consumeCharacter(sql, '>')) {
      return input.consumeCharacter(sql, '=')
          ? SqlComparison.GREATER_OR_EQUAL : SqlComparison.GREATER_THAN;
    }
    return null;
  }
}
