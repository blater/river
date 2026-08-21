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
    if (status.isOk()) status = expressions.parsePredicateScratch(sql, command, right);
    int leaf = status.isOk() ? target.appendLeaf(right) : -2;
    if (status.isOk() && leaf < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    if (status.isOk() && !target.setSubqueryComparison(
        leaf, reverse(comparison), subqueries.edge(synthetic))) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    if (status.isOk()) {
      subqueries.setLeaf(synthetic, leaf);
      node = target.appendBoolean(SqlBooleanPredicateProgram.BOOLEAN_LEAF, leaf, 0);
      if (node < 0) status = StatusCode.RESOURCE_EXHAUSTED;
    }
    return status;
  }

  int node() {
    return node;
  }

  private static boolean unsupported(SqlComparison comparison) {
    return comparison == null || comparison == SqlComparison.HALF_OPEN_RANGE
        || comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN;
  }

  private static SqlComparison reverse(SqlComparison comparison) {
    return switch (comparison) {
      case LESS_THAN -> SqlComparison.GREATER_THAN;
      case LESS_OR_EQUAL -> SqlComparison.GREATER_OR_EQUAL;
      case GREATER_THAN -> SqlComparison.LESS_THAN;
      case GREATER_OR_EQUAL -> SqlComparison.LESS_OR_EQUAL;
      default -> comparison;
    };
  }
}
