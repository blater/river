package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Parses one canonical bounded Boolean WHERE program and its access edges. */
final class SqlPredicateParser {
  private final SqlBooleanWhereParser booleans;
  private final SqlComparisonParser comparisons;

  SqlPredicateParser(
      SqlParserInput parserInput, SqlScalarExpressionParser expressionParser) {
    booleans = new SqlBooleanWhereParser(parserInput, expressionParser);
    comparisons = new SqlComparisonParser(parserInput);
  }

  void beginStandard() {
    booleans.beginStandard();
  }

  void beginSynthetic(int replacementOffset) {
    booleans.beginSynthetic(replacementOffset);
  }

  int syntheticPredicateIndex() {
    return booleans.syntheticLeaf();
  }

  StatusCode parse(
      CharSequence sql,
      SqlCommand result,
      boolean qualified) {
    StatusCode status = booleans.parse(sql, result);
    if (status.isOk() && qualified
        && !result.wherePredicates().allColumnsQualified(result)) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return status;
  }

  SqlComparison comparisonOperator(CharSequence sql) {
    return comparisons.parse(sql);
  }
}
