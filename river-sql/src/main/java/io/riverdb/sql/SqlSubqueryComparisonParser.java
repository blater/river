package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;

/** Normalizes a scalar subquery on the left into the canonical right-result leaf. */
final class SqlSubqueryComparisonParser {
  private final SqlParserInput input;
  private final SqlScalarExpressionParser expressions;
  private final SqlComparisonParser comparisons;
  private int node = -1;

  SqlSubqueryComparisonParser(
      SqlParserInput parserInput, SqlScalarExpressionParser scalarExpressions) {
    input = parserInput;
    expressions = scalarExpressions;
    comparisons = new SqlComparisonParser(parserInput);
  }

  StatusCode parseLeft(
      CharSequence sql,
      int synthetic,
      SqlCommand command,
      SqlBooleanPredicateProgram target,
      SqlSubqueryLeafRegistry subqueries,
      SqlScalarExpression right) {
    node = -1;
    if (expressions == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    StatusCode status = input.requireCharacter(sql, '0');
    SqlComparison comparison = status.isOk() ? comparisons.parse(sql) : null;
    if (status.isOk() && unsupported(comparison)) status = StatusCode.INVALID_EXTERNAL_INPUT;
    input.skipSpaces(sql);
    if (status.isOk()
        && subqueries.find(input.position(), SqlQuery.SUBQUERY_SCALAR) >= 0) {
      status = StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (!status.isOk()) return status;
    return appendRight(sql, command, target, subqueries, synthetic, right, comparison);
  }

  private StatusCode appendRight(
      CharSequence sql, SqlCommand command, SqlBooleanPredicateProgram target,
      SqlSubqueryLeafRegistry subqueries, int synthetic, SqlScalarExpression right,
      SqlComparison comparison) {
    StatusCode status = expressions.parsePredicateScratch(sql, command, right);
    if (!status.isOk()) return status;
    int leaf = target.appendLeaf(right);
    if (leaf < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (!target.setSubqueryComparison(
        leaf, comparison.reverseOrder(), subqueries.edge(synthetic))) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    subqueries.setLeaf(synthetic, leaf);
    node = target.appendBoolean(SqlBooleanPredicateProgram.BOOLEAN_LEAF, leaf, 0);
    return node < 0 ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  int node() {
    return node;
  }

  private static boolean unsupported(SqlComparison comparison) {
    return comparison == null || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN;
  }

}
