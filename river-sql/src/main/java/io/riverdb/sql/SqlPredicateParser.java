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

  void beginSubqueries(int[] offsets, int[] kinds, int[] edges, int count) {
    booleans.beginSubqueries(offsets, kinds, edges, count);
  }

  int subqueryLeaf(int index) {
    return booleans.subqueryLeaf(index);
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

  StatusCode parseOn(
      CharSequence sql,
      SqlCommand result,
      SqlBooleanPredicateProgram destination) {
    return booleans.parseOn(sql, result, destination);
  }

  SqlComparison comparisonOperator(CharSequence sql) {
    return comparisons.parse(sql);
  }
}
